package cloud.cleo.clamav.test;

import cloud.cleo.clamav.ScanStatus;
import static cloud.cleo.clamav.ScanStatus.ONLY_TAG_INFECTED;
import static cloud.cleo.clamav.ScanStatus.SCAN_TAG_NAME;
import java.io.PrintWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/**
 * Test to validate the container Lambda can properly set tags and identify a known infected file.
 *
 * In order for tests to run, you need to set VALIDATION_BUCKET in workflow and of course populate that bucket with test
 * files.
 *
 * @author sjensen
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class VirusScanValidationTest {

    private static final String BUCKET_NAME = System.getenv("VALIDATION_BUCKET");
    private static final String INFECTED_KEY = "eicar.txt";
    private static final String CLEAN_KEY = "NotInfectedFile.pdf";
    private static final String OVERSIZED_KEY = "large-test-file.zip";

    private static final S3Client s3 = S3Client.create();

    @BeforeAll
    static void checkSetup() {
        if (BUCKET_NAME == null || BUCKET_NAME.isEmpty()) {
            throw new IllegalStateException("VALIDATION_BUCKET environment variable must be set.");
        }
        
        // Ensure all tags are cleared before testing starts
        clearTags(INFECTED_KEY);
        clearTags(CLEAN_KEY);
        clearTags(OVERSIZED_KEY);
        
        //throw new AssertionError("Testing rollback");
    }

    /**
     * When all tagging is enabled, right before scanning takes place, it should be in SCANNING state, so this test is
     * forced at Order(1) to ensure it tests this first.
     *
     * @throws InterruptedException
     */
    @Test
    @Order(1)
    public void validateScanningTagSetImmediatelyIfNotOnlyInfected() throws InterruptedException {
        // This will show as aborted in test results (won't error out, but in reality its a skip)
        assumeTrue(!ONLY_TAG_INFECTED, "Skipping because ONLY_TAG_INFECTED is true");

        retriggerScan(INFECTED_KEY);
        // Tag should be applied pretty fast as soon as Lambda kicks off
        waitForTagValue(INFECTED_KEY, ScanStatus.SCANNING, Duration.ofSeconds(30));
    }

    /**
     * Validate a known virus file (EICAR Signature) tests to be INFECTED.
     *
     * @throws InterruptedException
     */
    @Test
    @Order(2)
    public void validateScanOfKnownInfectedFile() throws InterruptedException {
        if (ONLY_TAG_INFECTED) {
            // If SCANNING test was skipped, re-trigger scan here
            retriggerScan(INFECTED_KEY);
        }
        // Need to wait for scan to complete, which sometimes can take over a minute
        waitForTerminalTagValue(INFECTED_KEY, ScanStatus.INFECTED, Duration.ofMinutes(2));
    }

    /**
     * Validate a known clean file tests to be CLEAN. This catches false positives where ClamAV
     * fails to start but the pipeline still reports files as infected.
     *
     * @throws InterruptedException
     */
    @Test
    @Order(3)
    public void validateScanOfKnownCleanFile() throws InterruptedException {
        assumeTrue(!ONLY_TAG_INFECTED, "Skipping because clean validation requires ONLY_TAG_INFECTED=false");

        retriggerScan(CLEAN_KEY);
        waitForTerminalTagValue(CLEAN_KEY, ScanStatus.CLEAN, Duration.ofMinutes(2));
    }

    /**
     * When file to scan is over MAX_BYTES, then ensure it is not scanned and immediately tagged FILE_SIZE_EXCEEDED.
     *
     * @throws InterruptedException
     */
    @Test
    @Order(4)
    public void validateScanOfOversizedFile() throws InterruptedException {
        retriggerScan(OVERSIZED_KEY);
        // Should be detected before scanning on the S3 Head operation
        waitForTerminalTagValue(OVERSIZED_KEY, ScanStatus.FILE_SIZE_EXCEEED, Duration.ofSeconds(30));
    }

    /**
     * Close S3 client at end to shut down threads.
     */
    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            s3.close();
        }
    }

    /**
     * Wait up for scan tag to have an expected value.
     *
     * @param key
     * @param expectedValue
     * @param timeout
     * @throws InterruptedException
     */
    private static void waitForTagValue(String key, ScanStatus expectedValue, Duration timeout) throws InterruptedException {
        long timeoutMillis = timeout.toMillis();
        long sleepMillis = 5000;
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start <= timeoutMillis) {
            List<Tag> tags = getTags(key);
            String actual = tags.stream()
                    .filter(tag -> SCAN_TAG_NAME.equals(tag.key()))
                    .map(Tag::value)
                    .findFirst()
                    .orElse(null);

            if (expectedValue.name().equals(actual)) {
                assertThat(actual).isEqualTo(expectedValue.name());
                return;
            }

            Thread.sleep(sleepMillis);
        }

        throw new AssertionError("Timed out waiting for scan-status tag: " + expectedValue + " on key: " + key);
    }

    private static void waitForTerminalTagValue(String key, ScanStatus expectedValue, Duration timeout) throws InterruptedException {
        long timeoutMillis = timeout.toMillis();
        long sleepMillis = 5000;
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start <= timeoutMillis) {
            List<Tag> tags = getTags(key);
            String actual = tags.stream()
                    .filter(tag -> SCAN_TAG_NAME.equals(tag.key()))
                    .map(Tag::value)
                    .findFirst()
                    .orElse(null);

            if (expectedValue.name().equals(actual)) {
                assertThat(actual).isEqualTo(expectedValue.name());
                return;
            }

            if (actual != null) {
                ScanStatus actualStatus = ScanStatus.valueOf(actual);
                if (actualStatus != ScanStatus.SCANNING) {
                    throw new AssertionError("Expected scan-status " + expectedValue + " but found " + actualStatus + " on key: " + key);
                }
            }

            Thread.sleep(sleepMillis);
        }

        throw new AssertionError("Timed out waiting for scan-status tag: " + expectedValue + " on key: " + key);
    }

    /**
     * Get all tags on S3 Object.
     *
     * @param key
     * @return
     */
    private static List<Tag> getTags(String key) {
        GetObjectTaggingResponse response = s3.getObjectTagging(GetObjectTaggingRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build());
        return response.tagSet();
    }

    /**
     * Remove any tags on the object.
     *
     * @param key
     */
    private static void clearTags(String key) {
        s3.deleteObjectTagging(DeleteObjectTaggingRequest.builder()
                .bucket(BUCKET_NAME)
                .key(key)
                .build());
    }

    /**
     * Copy file onto itself to trigger a ObjectCreate event to start scanning.
     *
     * @param key
     */
    private static void retriggerScan(String key) {
        CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                .sourceBucket(BUCKET_NAME)
                .sourceKey(key)
                .destinationBucket(BUCKET_NAME)
                .destinationKey(key)
                .metadataDirective(MetadataDirective.COPY)
                .build();

        s3.copyObject(copyRequest);
    }

    /**
     * Called via mvn exec:java
     *
     * @param args
     */
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(VirusScanValidationTest.class))
                .build();

        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request, listener);

        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));

        summary.getFailures().forEach(failure -> {
            System.err.println("Failure: " + failure.getTestIdentifier().getDisplayName());
            failure.getException().printStackTrace();
        });

        if (summary.getTotalFailureCount() > 0 || summary.getContainersFailedCount() > 0) {
            System.exit(1);
        }
    }
}
