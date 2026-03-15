# Builder stage: use a modern Ubuntu base so apt installs a supported ClamAV.
FROM ubuntu:24.04 AS builder

# Prevent interactive prompts.
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -y && \
    apt-get install -y clamav clamav-freshclam p7zip-full

# Copy Clamscan
RUN cp /usr/bin/clamscan /tmp/

# Detect platform and copy libraries
RUN mkdir -p /tmp/clamav_libs && \
    ARCH=$(uname -m) && \
    LIB_DIR="/lib/$ARCH-linux-gnu" && \
    echo "Detected architecture: $ARCH, copying libraries from $LIB_DIR" && \
    cp ${LIB_DIR}/libclamav.so.* /tmp/clamav_libs/ && \
    cp ${LIB_DIR}/libjson-c.so.* /tmp/clamav_libs/ && \
    cp ${LIB_DIR}/libbz2.so.* /tmp/clamav_libs/ && \
    cp ${LIB_DIR}/libltdl.so.* /tmp/clamav_libs/ && \
    cp ${LIB_DIR}/libxml2.so.* /tmp/clamav_libs/ && \
    cp ${LIB_DIR}/libmspack.so.* /tmp/clamav_libs/ && \
    cp ${LIB_DIR}/libcrypto.so.* /tmp/clamav_libs/ && \
    cp ${LIB_DIR}/libz.so.* /tmp/clamav_libs/ && \
    (cp ${LIB_DIR}/libtfm.so.* /tmp/clamav_libs/ || true) && \
    cp ${LIB_DIR}/libpcre2-8.so.* /tmp/clamav_libs/ && \
    cp ${LIB_DIR}/liblzma.so.* /tmp/clamav_libs/


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
