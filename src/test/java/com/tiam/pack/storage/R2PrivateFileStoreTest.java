package com.tiam.pack.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class R2PrivateFileStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);

    private final R2PrivateFileStore store = new R2PrivateFileStore(
            "acct123", "tiam-packs", new S3Presigner("AKID", "SECRET-VALUE", "auto", CLOCK));

    @Test
    void signedUrl_pointsToTheAccountEndpointWithTheBucketInThePath() {
        String url = store.signedDownloadUrl("packs/v1/familias/a4/pack-completo.pdf", Duration.ofSeconds(300),
                "TIAM-Pack-Familias-A4-completo.pdf");

        assertThat(url).startsWith("https://acct123.r2.cloudflarestorage.com/tiam-packs/packs/v1/familias/a4/pack-completo.pdf?");
    }

    @Test
    void signedUrl_expiresInTheRequestedTime_andUsesTheAutoRegion() {
        String url = store.signedDownloadUrl("packs/v1/x.pdf", Duration.ofSeconds(300), "x.pdf");

        assertThat(url).contains("X-Amz-Expires=300");
        assertThat(url).contains("%2Fauto%2Fs3%2Faws4_request");
        assertThat(url).contains("X-Amz-Date=20261014T120000Z");
    }

    @Test
    void signedUrl_forcesADownloadWithTheGivenFileName() {
        String url = store.signedDownloadUrl("packs/v1/x.pdf", Duration.ofSeconds(300), "TIAM-Pack-Familias-A4-completo.pdf");

        assertThat(url).contains("response-content-disposition=attachment%3B%20filename%3D%22TIAM-Pack-Familias-A4-completo.pdf%22");
    }

    @Test
    void signedUrl_neutralisesHeaderInjectionInTheFileName() {
        String url = store.signedDownloadUrl("packs/v1/x.pdf", Duration.ofSeconds(300), "evil\"\r\nX-Injected: 1.pdf");

        assertThat(url).doesNotContain("%0D").doesNotContain("%0A").doesNotContain("%22%0");
        assertThat(url).contains("filename%3D%22evil");
    }

    @Test
    void signedUrl_neverContainsTheSecretAccessKey() {
        String url = store.signedDownloadUrl("packs/v1/x.pdf", Duration.ofSeconds(300), "x.pdf");

        assertThat(url).doesNotContain("SECRET-VALUE");
    }

    @Test
    void signedUrl_refusesKeysThatCouldEscapeTheirPrefix() {
        assertThatThrownBy(() -> store.signedDownloadUrl("../secret", Duration.ofSeconds(300), "x.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.signedDownloadUrl("packs/../../other", Duration.ofSeconds(300), "x.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.signedDownloadUrl("/absolute", Duration.ofSeconds(300), "x.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.signedDownloadUrl("", Duration.ofSeconds(300), "x.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
