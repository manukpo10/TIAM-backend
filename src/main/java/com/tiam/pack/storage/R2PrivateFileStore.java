package com.tiam.pack.storage;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cloudflare R2 through its S3-compatible API: path-style URLs on the account endpoint
 * ({@code https://<account>.r2.cloudflarestorage.com/<bucket>/<key>}), region {@code auto}.
 * Presigning is local (no network call), so issuing a link is free and fast.
 */
public class R2PrivateFileStore implements PrivateFileStore {

    static final String REGION = "auto";
    private static final Pattern ACCOUNT_ID = Pattern.compile("[A-Za-z0-9]{1,64}");
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,62}");

    private final String host;
    private final String bucket;
    private final S3Presigner presigner;

    R2PrivateFileStore(String accountId, String bucket, S3Presigner presigner) {
        if (accountId == null || !ACCOUNT_ID.matcher(accountId).matches()) {
            throw new IllegalArgumentException("R2 account id is not valid");
        }
        if (bucket == null || !BUCKET.matcher(bucket).matches()) {
            throw new IllegalArgumentException("R2 bucket name is not valid");
        }
        this.host = accountId + ".r2.cloudflarestorage.com";
        this.bucket = bucket;
        this.presigner = presigner;
    }

    @Override
    public String signedDownloadUrl(String key, Duration ttl, String downloadFilename) {
        StorageKeys.requireSafe(key);
        String disposition = "attachment; filename=\"" + StorageKeys.safeFilename(downloadFilename) + "\"";
        return presigner.presignGet("https", host, "/" + bucket + "/" + key, ttl,
                Map.of("response-content-disposition", disposition));
    }
}
