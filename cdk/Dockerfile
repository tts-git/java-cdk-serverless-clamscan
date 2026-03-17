# Build directly on the Lambda Java 25 base image so ClamAV and its linked
# libraries come from the same AL2023 runtime family used in production.
FROM public.ecr.aws/lambda/java:25

RUN dnf install -y \
        clamav1.4 \
        clamav1.4-freshclam \
        7zip \
        7zip-standalone && \
    dnf clean all && \
    rm -rf /var/cache/dnf

# Refresh definitions during the image build so the Lambda has no runtime
# dependency on internet access for virus database updates.
RUN mkdir -p /var/task/clamav_defs && \
    chown 999:999 /var/task/clamav_defs && \
    chmod 775 /var/task/clamav_defs && \
    freshclam --stdout --datadir=/var/task/clamav_defs && \
    chmod -R a+rX /var/task/clamav_defs

# Copy Lambda function JAR into the image.
COPY lambda-jar/lambda-1.0.jar ${LAMBDA_TASK_ROOT}/lib/

# Specify the Lambda handler (in the format Package.Class::method).
CMD [ "cloud.cleo.clamav.lambda.ScanningLambda::handleRequest" ]
