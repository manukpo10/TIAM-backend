package com.tiam.pack.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Development and test store: files live in a folder and the "signed URL" points to this same
 * backend ({@code /packs/dev-files}), which checks an HMAC and an expiry before streaming the file.
 * The signing secret is random per process, so links die with the process.
 *
 * <p>It exists so the whole purchase-to-download flow can be exercised without a cloud account. It is
 * never selected in production unless the storage setting is missing (and that case logs a warning).
 */
public class LocalPrivateFileStore implements PrivateFileStore {

    static final String ENDPOINT = "/packs/dev-files";

    private final Path baseDir;
    private final String publicBaseUrl;
    private final byte[] secret;
    private final Clock clock;

    public LocalPrivateFileStore(Path baseDir, String publicBaseUrl, byte[] secret, Clock clock) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
        this.secret = secret.clone();
        this.clock = clock;
    }

    @Override
    public String signedDownloadUrl(String key, Duration ttl, String downloadFilename) {
        insideBaseDir(key);
        long expiresAt = clock.instant().plus(ttl).getEpochSecond();
        String name = StorageKeys.safeFilename(downloadFilename);
        return publicBaseUrl + ENDPOINT
                + "?key=" + urlEncode(key)
                + "&exp=" + expiresAt
                + "&name=" + urlEncode(name)
                + "&sig=" + sign(key, expiresAt, name);
    }

    /** The file behind a signed link, only if the signature is right, the link has not expired and the file exists. */
    public Optional<Path> resolveSigned(String key, long expiresAt, String name, String signature) {
        if (key == null || name == null || signature == null) {
            return Optional.empty();
        }
        if (expiresAt <= clock.instant().getEpochSecond()) {
            return Optional.empty();
        }
        byte[] expected = sign(key, expiresAt, name).getBytes(StandardCharsets.UTF_8);
        byte[] received = signature.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, received)) {
            return Optional.empty();
        }
        try {
            Path file = insideBaseDir(key);
            return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Path insideBaseDir(String key) {
        StorageKeys.requireSafe(key);
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Storage key is not allowed");
        }
        return resolved;
    }

    private String sign(String key, long expiresAt, String name) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String payload = key + "\n" + expiresAt + "\n" + name;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String urlEncode(String value) {
        // %20, not "+": containers and test harnesses disagree about decoding "+" in a query string.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
