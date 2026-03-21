package cloud.cleo.clamav.lambda;

import cloud.cleo.clamav.ScanStatus;
import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;

class ScanningLambdaTest {

    @Test
    void determineScanStatusReturnsInfectedWhenClamavReportsFoundSignature() {
        String output = """
                /tmp/upload.pdf: Win.Test.EICAR_HDB-1 FOUND
                ----------- SCAN SUMMARY -----------
                Infected files: 1
                """;

        assertThat(ScanningLambda.determineScanStatus(1, output)).isEqualTo(ScanStatus.INFECTED);
    }

    @Test
    void determineScanStatusTreatsStartupFailureAsErrorInsteadOfInfected() {
        String output = """
                clamscan: /lib64/libc.so.6: version `GLIBC_2.38' not found (required by clamscan)
                clamscan: /lib64/libm.so.6: version `GLIBC_2.38' not found (required by /usr/lib/clamav_libs/libclamav.so.12)
                """;

        assertThat(ScanningLambda.determineScanStatus(1, output)).isEqualTo(ScanStatus.ERROR);
    }

    @Test
    void determineScanStatusReturnsCleanForExitCodeZero() {
        assertThat(ScanningLambda.determineScanStatus(0, "/tmp/upload.pdf: OK"))
                .isEqualTo(ScanStatus.CLEAN);
    }

    @Test
    void isRetryableS3FailureReturnsFalseForAccessDenied() {
        CompletionException exception = new CompletionException(S3Exception.builder()
                .statusCode(403)
                .message("Access Denied")
                .build());

        assertThat(ScanningLambda.isRetryableS3Failure(exception)).isFalse();
    }

    @Test
    void isRetryableS3FailureReturnsTrueForServerErrors() {
        CompletionException exception = new CompletionException(S3Exception.builder()
                .statusCode(503)
                .message("Service Unavailable")
                .build());

        assertThat(ScanningLambda.isRetryableS3Failure(exception)).isTrue();
    }

    @Test
    void unwrapCompletionExceptionReturnsRootCause() {
        RuntimeException rootCause = new RuntimeException("boom");
        CompletionException exception = new CompletionException(new CompletionException(rootCause));

        assertThat(ScanningLambda.unwrapCompletionException(exception)).isSameAs(rootCause);
    }
}
