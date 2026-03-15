# Builder stage: use a modern Ubuntu base so apt installs a supported ClamAV.
FROM ubuntu:24.04 AS builder

# Prevent interactive prompts.
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -y && \
    apt-get install -y clamav clamav-freshclam p7zip-full

# Copy Clamscan
RUN cp /usr/bin/clamscan /tmp/

# Collect the runtime-linked shared libraries for clamscan and libclamav.
RUN mkdir -p /tmp/clamav_libs && \
    LIBCLAMAV=$(find /usr/lib -path '*/libclamav.so.*' | head -n 1) && \
    echo "Collecting runtime libraries for /usr/bin/clamscan and $LIBCLAMAV" && \
    ldd /usr/bin/clamscan "$LIBCLAMAV" | \
    awk '/=> \// { print $3 } /^\/[^[:space:]]+/ { print $1 }' | \
    sort -u | \
    xargs -I '{}' cp -v '{}' /tmp/clamav_libs/


# Create a directory for the definitions and run freshclam to update them.
RUN mkdir -p /tmp/clamav_defs && \
    chmod -R 777 /tmp/clamav_defs && \
    freshclam --stdout --datadir=/tmp/clamav_defs && \
    cp -R /tmp/clamav_defs /tmp/clamav_defs_output

# Final stage: use the AWS Lambda Java 21 base image.
FROM public.ecr.aws/lambda/java:21 AS final

# Copy the ClamAV executable from the builder stage.
COPY --from=builder /tmp/clamscan /usr/bin/clamscan

# Copy the virus definitions updated by freshclam.
COPY --from=builder /tmp/clamav_defs_output /var/task/clamav_defs

# Copy all ClamAV shared libraries from the builder stage.
COPY --from=builder /tmp/clamav_libs /usr/lib/clamav_libs

# Set the dynamic linker search path to include the directory with the ClamAV libraries.
ENV LD_LIBRARY_PATH="${LD_LIBRARY_PATH}:/usr/lib/clamav_libs"

# Copy Lambda function JAR into the image.
COPY lambda-jar/lambda-1.0.jar ${LAMBDA_TASK_ROOT}/lib/

# Specify the Lambda handler (in the format Package.Class::method).
CMD [ "cloud.cleo.clamav.lambda.ScanningLambda::handleRequest" ]
