package com.tiam.pack.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class S3PresignerTest {

    // The credentials, date and expected signature below are the worked example published in the
    // AWS Signature Version 4 documentation ("Authenticating requests: using query parameters").
    private static final String AWS_EXAMPLE_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String AWS_EXAMPLE_SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final Clock AWS_EXAMPLE_CLOCK =
            Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void presignGet_reproducesTheAwsDocumentationExampleSignature() {
        S3Presigner presigner = new S3Presigner(AWS_EXAMPLE_ACCESS_KEY, AWS_EXAMPLE_SECRET, "us-east-1", AWS_EXAMPLE_CLOCK);

        String url = presigner.presignGet("https", "examplebucket.s3.amazonaws.com", "/test.txt",
                Duration.ofSeconds(86400), Map.of());

        assertThat(url).isEqualTo("https://examplebucket.s3.amazonaws.com/test.txt"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20130524T000000Z"
                + "&X-Amz-Expires=86400"
                + "&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
    }

    @Test
    void presignGet_changingAnyInputChangesTheSignature() {
        S3Presigner presigner = new S3Presigner(AWS_EXAMPLE_ACCESS_KEY, AWS_EXAMPLE_SECRET, "us-east-1", AWS_EXAMPLE_CLOCK);
        String base = presigner.presignGet("https", "h.example.com", "/b/k.pdf", Duration.ofSeconds(300), Map.of());

        assertThat(signatureOf(presigner.presignGet("https", "h.example.com", "/b/other.pdf", Duration.ofSeconds(300), Map.of())))
                .isNotEqualTo(signatureOf(base));
        assertThat(signatureOf(presigner.presignGet("https", "other.example.com", "/b/k.pdf", Duration.ofSeconds(300), Map.of())))
                .isNotEqualTo(signatureOf(base));
        assertThat(signatureOf(presigner.presignGet("https", "h.example.com", "/b/k.pdf", Duration.ofSeconds(301), Map.of())))
                .isNotEqualTo(signatureOf(base));
        assertThat(signatureOf(presigner.presignGet("https", "h.example.com", "/b/k.pdf", Duration.ofSeconds(300),
                Map.of("response-content-disposition", "attachment"))))
                .isNotEqualTo(signatureOf(base));
    }

    @Test
    void presignGet_encodesTheExtraQueryParametersTheWayTheSignatureExpects() {
        S3Presigner presigner = new S3Presigner(AWS_EXAMPLE_ACCESS_KEY, AWS_EXAMPLE_SECRET, "auto", AWS_EXAMPLE_CLOCK);

        String url = presigner.presignGet("https", "acct.r2.cloudflarestorage.com", "/bucket/packs/v1/a b.pdf",
                Duration.ofSeconds(300),
                Map.of("response-content-disposition", "attachment; filename=\"TIAM Pack.pdf\""));

        // spaces are %20 (never +), quotes %22, semicolons %3B; path keeps its slashes
        assertThat(url).contains("/bucket/packs/v1/a%20b.pdf?");
        assertThat(url).contains("response-content-disposition=attachment%3B%20filename%3D%22TIAM%20Pack.pdf%22");
        assertThat(url).doesNotContain("+");
        // lower-case parameter sorts after every X-Amz-* one, and the signature is appended last
        assertThat(url.indexOf("X-Amz-SignedHeaders")).isLessThan(url.indexOf("response-content-disposition"));
        assertThat(url).endsWith("&X-Amz-Signature=" + signatureOf(url));
    }

    @Test
    void presignGet_neverContainsTheSecret() {
        S3Presigner presigner = new S3Presigner(AWS_EXAMPLE_ACCESS_KEY, AWS_EXAMPLE_SECRET, "auto", AWS_EXAMPLE_CLOCK);

        String url = presigner.presignGet("https", "h.example.com", "/b/k", Duration.ofSeconds(300), Map.of());

        assertThat(url).doesNotContain(AWS_EXAMPLE_SECRET).doesNotContain("EXAMPLEKEY");
    }

    @Test
    void presignGet_rejectsAnExpiryOutsideWhatSigV4Allows() {
        S3Presigner presigner = new S3Presigner(AWS_EXAMPLE_ACCESS_KEY, AWS_EXAMPLE_SECRET, "auto", AWS_EXAMPLE_CLOCK);

        assertThatThrownBy(() -> presigner.presignGet("https", "h", "/k", Duration.ZERO, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> presigner.presignGet("https", "h", "/k", Duration.ofDays(8), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String signatureOf(String url) {
        int at = url.indexOf("X-Amz-Signature=");
        String tail = url.substring(at + "X-Amz-Signature=".length());
        int amp = tail.indexOf('&');
        return amp < 0 ? tail : tail.substring(0, amp);
    }
}
