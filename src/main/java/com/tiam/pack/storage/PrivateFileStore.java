package com.tiam.pack.storage;

import java.time.Duration;

/**
 * Where the paid files live, seen from the rest of the app: private storage that hands out
 * short-lived links. Cloudflare R2 in production, a local folder in development. Moving to another
 * provider means writing another implementation, nothing else.
 */
public interface PrivateFileStore {

    /**
     * A URL the buyer's browser can GET for the next {@code ttl}; the file is saved as
     * {@code downloadFilename}. The object itself stays private: only this URL grants access.
     *
     * @throws IllegalArgumentException if the key could escape its prefix
     * @throws StorageUnavailableException if the store is misconfigured
     */
    String signedDownloadUrl(String key, Duration ttl, String downloadFilename);
}
