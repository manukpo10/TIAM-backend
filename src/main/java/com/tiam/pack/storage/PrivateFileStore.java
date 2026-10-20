package com.tiam.pack.storage;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

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

    /**
     * Which of these storage keys are NOT in the store, or could not be verified (empty = every object is there). Sales
     * of a product stay closed until all its files are uploaded (see PackSalesReadiness), so a buyer is never charged
     * for a file that does not exist. A store that cannot tell reports nothing missing; the development folder and
     * the "unavailable" placeholder are refused by {@link #isRemote()} anyway.
     */
    default List<String> missingKeys(Collection<String> keys) {
        return List.of();
    }

    /**
     * True only for a real remote store a buyer's browser can reach (Cloudflare R2). The development folder and
     * the "unavailable" placeholder are not: sales must refuse to start with them (see PackSalesReadiness).
     */
    default boolean isRemote() {
        return false;
    }
}
