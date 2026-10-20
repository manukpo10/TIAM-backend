package com.tiam.pack.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * "Does every catalog object exist in R2?" answered with a signed HEAD (no object body is ever read) and a short
 * timeout. The HTTP call is faked: nothing here talks to Cloudflare.
 */
class R2ObjectCheckTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);
    private static final String KEY_1 = "packs/v1/cuadernos/a4/cuaderno-1.pdf";
    private static final String KEY_2 = "packs/v1/cuadernos/a4/cuaderno-2.pdf";
    private static final String KEY_3 = "packs/v1/cuadernos/a4/cuaderno-3.pdf";

    private final S3Presigner presigner = new S3Presigner("AKID", "SECRET-VALUE", "auto", CLOCK);
    private final List<String> probed = new ArrayList<>();

    private R2PrivateFileStore store(R2PrivateFileStore.HeadProbe probe) {
        return new R2PrivateFileStore("acct123", "tiam-packs", presigner, probe);
    }

    @Test
    void everyObjectPresent_nothingIsMissing() {
        R2PrivateFileStore store = store(url -> {
            probed.add(url);
            return 200;
        });

        assertThat(store.missingKeys(List.of(KEY_1, KEY_2, KEY_3))).isEmpty();
        assertThat(probed).hasSize(3);
    }

    @Test
    void anObjectR2DoesNotHave_isReportedMissing_theOthersAreNot() {
        R2PrivateFileStore store = store(url -> url.contains("cuaderno-2.pdf") ? 404 : 200);

        assertThat(store.missingKeys(List.of(KEY_1, KEY_2, KEY_3))).containsExactly(KEY_2);
    }

    @Test
    void aRefusal_isNotProofOfExistence_soThoseKeysAreReportedToo() {
        R2PrivateFileStore store = store(url -> 403); // bad credentials or permissions: nothing can be verified

        assertThat(store.missingKeys(List.of(KEY_1, KEY_2))).containsExactly(KEY_1, KEY_2);
    }

    @Test
    void whenR2CannotBeReached_theRestIsReportedWithoutWaitingOnEveryKey() {
        R2PrivateFileStore store = store(url -> {
            probed.add(url);
            if (probed.size() == 2) {
                throw new IOException("timed out");
            }
            return 200;
        });

        assertThat(store.missingKeys(List.of(KEY_1, KEY_2, KEY_3))).containsExactly(KEY_2, KEY_3);
        assertThat(probed).as("one failed attempt, then it stops").hasSize(2);
    }

    @Test
    void anInterruptedCheck_restoresTheInterruptFlag_andReportsTheRest() {
        R2PrivateFileStore store = store(url -> {
            throw new InterruptedException("shutdown");
        });
        try {
            assertThat(store.missingKeys(List.of(KEY_1, KEY_2))).containsExactly(KEY_1, KEY_2);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear the flag for the other tests
        }
    }

    @Test
    void theRequestIsASignedHead_forThatObject_validForAMinute_andReadsNoContent() {
        R2PrivateFileStore store = store(url -> {
            probed.add(url);
            return 200;
        });

        store.missingKeys(List.of(KEY_1));

        String url = probed.get(0);
        assertThat(url).startsWith("https://acct123.r2.cloudflarestorage.com/tiam-packs/" + KEY_1 + "?")
                .contains("X-Amz-Expires=60").contains("X-Amz-SignedHeaders=host").contains("X-Amz-Signature=")
                .doesNotContain("response-content-disposition").doesNotContain("SECRET-VALUE");
    }

    @Test
    void theHeadSignatureIsNotTheGetOne_becauseTheMethodIsPartOfWhatIsSigned() {
        String head = presigner.presign("HEAD", "https", "acct123.r2.cloudflarestorage.com", "/tiam-packs/" + KEY_1,
                Duration.ofSeconds(60), Map.of());
        String get = presigner.presignGet("https", "acct123.r2.cloudflarestorage.com", "/tiam-packs/" + KEY_1,
                Duration.ofSeconds(60), Map.of());

        assertThat(head).isNotEqualTo(get);
        assertThat(head.substring(0, head.indexOf("&X-Amz-Signature="))).isEqualTo(get.substring(0, get.indexOf("&X-Amz-Signature=")));
        assertThat(head).isEqualTo(presigner.presign("HEAD", "https", "acct123.r2.cloudflarestorage.com", "/tiam-packs/" + KEY_1,
                Duration.ofSeconds(60), Map.of())); // deterministic
    }

    @Test
    void theHeadSignature_matchesAnIndependentSigV4Computation() throws Exception {
        String host = "acct123.r2.cloudflarestorage.com";
        String path = "/tiam-packs/" + KEY_1;
        String url = presigner.presign("HEAD", "https", host, path, Duration.ofSeconds(60), Map.of());

        String query = url.substring(url.indexOf('?') + 1, url.indexOf("&X-Amz-Signature="));
        String canonicalRequest = "HEAD\n" + path + "\n" + query + "\nhost:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
        String scope = "20261014/auto/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n20261014T120000Z\n" + scope + "\n" + sha256(canonicalRequest);
        byte[] key = hmac(hmac(hmac(hmac(("AWS4SECRET-VALUE").getBytes(StandardCharsets.UTF_8), "20261014"), "auto"), "s3"),
                "aws4_request");

        assertThat(url).endsWith("&X-Amz-Signature=" + HexFormat.of().formatHex(hmac(key, stringToSign)));
    }

    @Test
    void aKeyThatCouldEscapeItsPrefix_isRefused_andNothingIsProbed() {
        R2PrivateFileStore store = store(url -> {
            probed.add(url);
            return 200;
        });

        assertThatThrownBy(() -> store.missingKeys(List.of(KEY_1, "../secret"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(probed).isEmpty();
    }

    @Test
    void nothingToCheck_meansNothingMissing() {
        R2PrivateFileStore store = store(url -> {
            probed.add(url);
            return 200;
        });

        assertThat(store.missingKeys(List.of())).isEmpty();
        assertThat(probed).isEmpty();
    }

    @Test
    void theDefaultStores_cannotTellAndReportNothing() {
        PrivateFileStore plain = (key, ttl, name) -> "https://example.com/x";

        assertThat(plain.missingKeys(List.of(KEY_1))).isEmpty();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}
