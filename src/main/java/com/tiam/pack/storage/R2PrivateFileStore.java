package com.tiam.pack.storage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Cloudflare R2 through its S3-compatible API: path-style URLs on the account endpoint
 * ({@code https://<account>.r2.cloudflarestorage.com/<bucket>/<key>}), region {@code auto}.
 * Presigning is local (no network call), so issuing a link is free and fast. The only call that goes out is the
 * existence check ({@link #missingKeys}), a signed HEAD per object with a short timeout.
 */
@Slf4j
public class R2PrivateFileStore implements PrivateFileStore {

    static final String REGION = "auto";
    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,62}");
    /** A HEAD URL only has to live for the one request. */
    private static final Duration HEAD_URL_TTL = Duration.ofSeconds(60);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    /** Sends the signed HEAD and returns the HTTP status; faked in tests. */
    @FunctionalInterface
    interface HeadProbe {
        int status(String signedUrl) throws IOException, InterruptedException;
    }

    private final String host;
    private final String bucket;
    private final S3Presigner presigner;
    private final HeadProbe probe;

    R2PrivateFileStore(String accountId, String bucket, S3Presigner presigner) {
        this(accountId, bucket, presigner, httpProbe());
    }

    R2PrivateFileStore(String accountId, String bucket, S3Presigner presigner, HeadProbe probe) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("R2 account id is not valid");
        }
        if (bucket == null || !BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("R2 bucket name is not valid");
        }
        this.host = accountId + ".r2.cloudflarestorage.com";
        this.bucket = bucket;
        this.presigner = presigner;
        this.probe = probe;
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    @Override
    public String signedDownloadUrl(String key, Duration ttl, String downloadFilename) {
        StorageKeys.requireSafe(key);
        String disposition = "attachment; filename=\"" + StorageKeys.safeFilename(downloadFilename) + "\"";
        return presigner.presignGet("https", host, "/" + bucket + "/" + key, ttl,
                Map.of("response-content-disposition", disposition));
    }

    /**
     * A signed HEAD per key (no object is downloaded). 200 means present; 404 means missing; any other answer (a 403
     * from bad credentials, a 5xx) cannot prove the object exists, so that key is reported too. When R2 cannot be
     * reached at all the first failure ends the check: that key and every one after it are reported, instead of
     * waiting out a timeout for each. The log says why.
     */
    @Override
    public List<String> missingKeys(Collection<String> keys) {
        keys.forEach(StorageKeys::requireSafe);
        List<String> missing = new ArrayList<>();
        boolean stopped = false;
        for (String key : keys) {
            if (stopped) {
                missing.add(key);
                continue;
            }
            String url = presigner.presign("HEAD", "https", host, "/" + bucket + "/" + key, HEAD_URL_TTL, Map.of());
            try {
                int status = probe.status(url);
                if (status != 200) {
                    missing.add(key);
                    log.warn("R2 object {} {}: HEAD answered {}", key, status == 404 ? "is MISSING" : "could not be verified", status);
                }
            } catch (IOException | RuntimeException e) {
                stopped = true;
                missing.add(key);
                log.warn("R2 could not be reached to check {} ({}): the remaining objects are reported as unverified",
                        key, e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
                missing.add(key);
            }
        }
        return missing;
    }

    private static HeadProbe httpProbe() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        return url -> client.send(
                HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(REQUEST_TIMEOUT).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
