package cloud.cleo.clamav.lambda;

import cloud.cleo.clamav.ScanStatus;
import static cloud.cleo.clamav.ScanStatus.MAX_BYTES;
import static cloud.cleo.clamav.ScanStatus.ONLY_TAG_INFECTED;
import static cloud.cleo.clamav.ScanStatus.SCAN_TAG_NAME;
import com.amazonaws.services.lambda.runtime.Context;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;

/**
 * Consume S3 Object create event and then scan Object and set Tag with result.
 *
 * @author sjensen
 */
public class ScanningLambda implements RequestHandler<S3EventNotification, Void> {
    private static final Pattern CLAMAV_FOUND_PATTERN = Pattern.compile("(?m)^.*\\bFOUND$");

    // Create an S3 client with CRT Async (better download performance and Async calls)
    final static S3AsyncClient s3Client = S3AsyncClient.crtCreate();

    // Configure a Log4j2 logger.
    final static Logger log = LogManager.getLogger(ScanningLambda.class);

    @Override
    public Void handleRequest(S3EventNotification event, Context context) {
        // There will only ever be one record
        event.getRecords().forEach(record -> {
            String bucket = record.getS3().getBucket().getName();
            String key = record.getS3().getObject().getUrlDecodedKey();
            String versionId = normalizeVersionId(record.getS3().getObject().getVersionId());

            log.info("Processing file from bucket: {}, key: {}, versionId: {}", bucket, key, versionId);

            if (bucket == null || bucket.isEmpty() || key == null || key.isEmpty()) {
                log.error("Invalid S3 event: bucket and key must be provided");
                return;
            }

            // Object create events include size, so avoid a separate HeadObject call before downloading.
            Long size = record.getS3().getObject().getSizeAsLong();
            if (size != null) {
                if (size > MAX_BYTES) {
                    log.warn("Skipping file {} due to size ({} bytes) exceeding max of {} bytes", key, size, MAX_BYTES);
                    setScanTagStatus(bucket, key, versionId, ScanStatus.FILE_SIZE_EXCEEED).join();
                    return;
                }
            } else {
                log.warn("S3 event did not include object size for bucket: {}, key: {}. Proceeding with download.", bucket,
                        key);
            }

            // Download the file to /tmp with a unique file name.
            Path localFilePath = createTempFilePath(key);
            try {
                log.info("Downloading file {} from bucket {} to {}", key, bucket, localFilePath);
                GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .versionId(versionId)
                        .build();
                s3Client.getObject(getObjectRequest, localFilePath)
                        .join(); // Wait for completion before proceeding
            } catch (CompletionException e) {
                if (isRetryableS3Failure(e)) {
                    log.error("Retryable S3 failure during download for bucket: {}, key: {}", bucket, key, e);
                    throw e;
                }

                log.error("Non-retryable S3 failure during download for bucket: {}, key: {}. Check Lambda IAM, bucket "
                        + "policy, object ownership, and SSE-KMS permissions.", bucket, key, unwrapCompletionException(e));
                markObjectError(bucket, key, versionId);
                throw e;
            }

            if (!ONLY_TAG_INFECTED) {
                // Mark the object as SCANNING only after we have a local copy so bucket policy does not block the
                // Lambda's own GetObject request.
                setScanTagStatus(bucket, key, versionId, ScanStatus.SCANNING).join();
            }

            // Run ClamAV (clamscan) on the downloaded file.
            ScanStatus status;
            try {
                log.info("Running clamscan on file: {}", localFilePath);
                ProcessBuilder pb = new ProcessBuilder(
                        "clamscan",
                        "-v",
                        "--database=/var/task/clamav_defs",
                        "--stdout",
                        "--max-filesize=" + MAX_BYTES,
                        "--max-scansize=" + MAX_BYTES,
                        "-r",
                        "--tempdir=/tmp",
                        localFilePath.toString()
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                int remainingMillis = context.getRemainingTimeInMillis();
                log.info("Remaining Millis before Lambda will timeout: {}", remainingMillis);

                // Wait up till the amount of time left the Lambda has with 10 second buffer
                long waitMillis = getClamScanWaitMillis(remainingMillis);
                if (waitMillis <= 0) {
                    process.destroyForcibly();
                    log.error("Not enough execution time left to safely run clamscan. Remaining millis: {}", remainingMillis);
                    if (!ONLY_TAG_INFECTED) {
                        setScanTagStatus(bucket, key, versionId, ScanStatus.ERROR).join();
                    }
                    return;
                }

                boolean finished = process.waitFor(waitMillis, TimeUnit.MILLISECONDS);

                if (!finished) {
                    process.destroyForcibly();
                    log.error("clamscan process timed out!");
                    if (!ONLY_TAG_INFECTED) {
                        setScanTagStatus(bucket, key, versionId, ScanStatus.ERROR).join(); // Wait for result before exiting
                    }
                    return;
                }

                String processOutput;
                try (final var is = process.getInputStream()) {
                    processOutput = new String(is.readAllBytes());
                    log.debug("Process Output: {}", processOutput);
                }

                status = determineScanStatus(process.exitValue(), processOutput);
                log.info("Scan result for {}: {}", key, status);
            } catch (IOException | InterruptedException e) {
                log.error("Error running clamscan: ", e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return;
            } catch (CompletionException e) {
                if (isRetryableS3Failure(e)) {
                    log.error("Retryable S3 failure while updating scan state for bucket: {}, key: {}", bucket, key, e);
                    throw e;
                }

                log.error("Non-retryable S3 failure while updating scan state for bucket: {}, key: {}. Check Lambda "
                        + "IAM, bucket policy, object ownership, and SSE-KMS permissions.", bucket, key,
                        unwrapCompletionException(e));
                markObjectError(bucket, key, versionId);
                throw e;
            } finally {
                try {
                    Files.deleteIfExists(localFilePath);
                } catch (IOException e) {
                    log.warn("Warning: Could not delete local file {}: {}", localFilePath, e.getMessage());
                }
            }

            if (ONLY_TAG_INFECTED && !ScanStatus.INFECTED.equals(status)) {
                // Scan is not INFECTED, so do not set tagging 
                log.debug("Not setting tag on Object because of flag and file is not INFECTED");
                return;
            }

            // Update the S3 object's tagging with the scan result.
            try {
                setScanTagStatus(bucket, key, versionId, status).join(); // Wait for result before exiting
            } catch (CompletionException e) {
                if (isRetryableS3Failure(e)) {
                    log.error("Retryable S3 failure while tagging object with final scan status {} for bucket: {}, key: {}",
                            status, bucket, key, e);
                    throw e;
                }

                log.error("Non-retryable S3 failure while tagging object with final scan status {} for bucket: {}, key: {}",
                        status, bucket, key, unwrapCompletionException(e));
                throw e;
            }

        });
        return null;
    }

    /**
     * Add/Update Scan Status tag to S3 Object. This method preserves any other tags that may be on the Object.
     *
     * @param bucket
     * @param key
     * @param versionId
     * @param status
     */
    private CompletableFuture<PutObjectTaggingResponse> setScanTagStatus(String bucket, String key, String versionId,
            ScanStatus status) {
        try {
            // Get current tags
            List<Tag> existingTags = s3Client.getObjectTagging(b -> b.bucket(bucket).key(key).versionId(versionId))
                    .join() // Get result now since we need in order to put all tags
                    .tagSet();

            // Remove any existing "scan-status" tag
            List<Tag> updatedTags = new ArrayList<>();
            for (Tag tag : existingTags) {
                if (!tag.key().equals(SCAN_TAG_NAME)) {
                    updatedTags.add(tag);
                }
            }

            // Add updated scan-status tag
            updatedTags.add(Tag.builder()
                    .key(SCAN_TAG_NAME)
                    .value(status.toString())
                    .build());

            // Put updated tag set
            Tagging tagging = Tagging.builder()
                    .tagSet(updatedTags)
                    .build();
            PutObjectTaggingRequest putTaggingRequest = PutObjectTaggingRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .versionId(versionId)
                    .tagging(tagging)
                    .build();

            log.info("Updating object tags for {} with scan-status: {}", key, status);
            return s3Client.putObjectTagging(putTaggingRequest);
        } catch (Exception e) {
            log.error("Error updating object tags", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    static long getClamScanWaitMillis(int remainingMillis) {
        return Math.max(0, remainingMillis - 10000L);
    }

    static ScanStatus determineScanStatus(int exitCode, String processOutput) {
        if (exitCode == 0) {
            return ScanStatus.CLEAN;
        }

        if (exitCode == 1) {
            if (processOutput != null && CLAMAV_FOUND_PATTERN.matcher(processOutput).find()) {
                return ScanStatus.INFECTED;
            }

            log.error("clamscan exited with code 1 but did not report a FOUND signature. Treating as ERROR. Output: {}",
                    processOutput);
            return ScanStatus.ERROR;
        }

        return ScanStatus.ERROR;
    }

    static boolean isRetryableS3Failure(Throwable throwable) {
        Throwable unwrapped = unwrapCompletionException(throwable);
        if (unwrapped instanceof S3Exception s3Exception) {
            int statusCode = s3Exception.statusCode();
            return statusCode == 408 || statusCode == 429 || statusCode >= 500;
        }

        // SDK/client-side failures such as timeouts or connection resets are usually worth retrying.
        return true;
    }

    static Throwable unwrapCompletionException(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static String normalizeVersionId(String versionId) {
        if (versionId == null || versionId.isBlank() || "null".equalsIgnoreCase(versionId)) {
            return null;
        }
        return versionId;
    }

    static Path createTempFilePath(String key) {
        String baseName = new File(key).getName();
        String extension = "";
        int extIndex = baseName.lastIndexOf('.');
        if (extIndex > 0 && extIndex < baseName.length() - 1) {
            extension = baseName.substring(extIndex);
        }

        String uniqueName = java.util.UUID.randomUUID() + extension;
        return Paths.get("/tmp", uniqueName);
    }

    private void markObjectError(String bucket, String key, String versionId) {
        if (ONLY_TAG_INFECTED) {
            return;
        }

        try {
            setScanTagStatus(bucket, key, versionId, ScanStatus.ERROR).join();
        } catch (CompletionException e) {
            log.warn("Could not tag object with ERROR status for bucket: {}, key: {}", bucket, key,
                    unwrapCompletionException(e));
        }
    }
}
