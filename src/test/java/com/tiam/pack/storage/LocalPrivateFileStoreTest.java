package com.tiam.pack.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalPrivateFileStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final byte[] SECRET = "unit-test-signing-secret-32-bytes!".getBytes();

    @TempDir Path dir;
    LocalPrivateFileStore store;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(dir.resolve("packs/v1/familias/a4"));
        Files.writeString(dir.resolve("packs/v1/familias/a4/pack-completo.pdf"), "PDF-BYTES");
        store = new LocalPrivateFileStore(dir, "http://localhost:8080", SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void signedUrl_pointsToTheLocalDevEndpointAndExpiresInTheRequestedTime() {
        String url = store.signedDownloadUrl("packs/v1/familias/a4/pack-completo.pdf", Duration.ofSeconds(300), "x.pdf");

        Map<String, String> query = query(url);
        assertThat(url).startsWith("http://localhost:8080/packs/dev-files?");
        assertThat(query.get("key")).isEqualTo("packs/v1/familias/a4/pack-completo.pdf");
        assertThat(query.get("exp")).isEqualTo(String.valueOf(NOW.plusSeconds(300).getEpochSecond()));
        assertThat(query.get("name")).isEqualTo("x.pdf");
        assertThat(query.get("sig")).matches("[0-9a-f]{64}");
    }

    @Test
    void resolve_validSignature_returnsTheFile() throws IOException {
        Map<String, String> q = query(store.signedDownloadUrl("packs/v1/familias/a4/pack-completo.pdf", Duration.ofSeconds(300), "x.pdf"));

        Optional<Path> file = store.resolveSigned(q.get("key"), Long.parseLong(q.get("exp")), q.get("name"), q.get("sig"));

        assertThat(file).isPresent();
        assertThat(Files.readString(file.get())).isEqualTo("PDF-BYTES");
    }

    @Test
    void resolve_expiredLink_isRefused() {
        Map<String, String> q = query(store.signedDownloadUrl("packs/v1/familias/a4/pack-completo.pdf", Duration.ofSeconds(300), "x.pdf"));
        LocalPrivateFileStore later = new LocalPrivateFileStore(dir, "http://localhost:8080", SECRET,
                Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC));

        assertThat(later.resolveSigned(q.get("key"), Long.parseLong(q.get("exp")), q.get("name"), q.get("sig"))).isEmpty();
    }

    @Test
    void resolve_tamperedKeyExpiryNameOrSignature_isRefused() {
        Map<String, String> q = query(store.signedDownloadUrl("packs/v1/familias/a4/pack-completo.pdf", Duration.ofSeconds(300), "x.pdf"));
        long exp = Long.parseLong(q.get("exp"));

        assertThat(store.resolveSigned("packs/v1/familias/a4/other.pdf", exp, q.get("name"), q.get("sig"))).isEmpty();
        assertThat(store.resolveSigned(q.get("key"), exp + 3600, q.get("name"), q.get("sig"))).isEmpty();
        assertThat(store.resolveSigned(q.get("key"), exp, "other.pdf", q.get("sig"))).isEmpty();
        assertThat(store.resolveSigned(q.get("key"), exp, q.get("name"), "0".repeat(64))).isEmpty();
        assertThat(store.resolveSigned(q.get("key"), exp, q.get("name"), null)).isEmpty();
    }

    @Test
    void resolve_aSignatureFromAnotherProcess_isRefused() {
        Map<String, String> q = query(store.signedDownloadUrl("packs/v1/familias/a4/pack-completo.pdf", Duration.ofSeconds(300), "x.pdf"));
        LocalPrivateFileStore other = new LocalPrivateFileStore(dir, "http://localhost:8080",
                "a-different-secret-of-32-bytes!!!!".getBytes(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(other.resolveSigned(q.get("key"), Long.parseLong(q.get("exp")), q.get("name"), q.get("sig"))).isEmpty();
    }

    @Test
    void signedUrl_refusesKeysThatEscapeTheStorageDirectory() {
        assertThatThrownBy(() -> store.signedDownloadUrl("../outside.txt", Duration.ofSeconds(300), "x.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.signedDownloadUrl("packs/../../outside.txt", Duration.ofSeconds(300), "x.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_missingFile_isRefusedEvenWithAValidSignature() {
        Map<String, String> q = query(store.signedDownloadUrl("packs/v1/familias/a4/missing.pdf", Duration.ofSeconds(300), "x.pdf"));

        assertThat(store.resolveSigned(q.get("key"), Long.parseLong(q.get("exp")), q.get("name"), q.get("sig"))).isEmpty();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> result = new HashMap<>();
        String raw = URI.create(url).getRawQuery();
        Arrays.stream(raw.split("&")).forEach(pair -> {
            int eq = pair.indexOf('=');
            result.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
        });
        return result;
    }
}
