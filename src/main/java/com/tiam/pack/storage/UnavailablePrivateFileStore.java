package com.tiam.pack.storage;

import java.time.Duration;

/**
 * Stands in when the storage setting is unusable. The app still boots (a bad pack setting must
 * never take the Desafío down with it); only the download links fail, loudly and in one place.
 */
final class UnavailablePrivateFileStore implements PrivateFileStore {

    private final String reason;

    UnavailablePrivateFileStore(String reason) {
        this.reason = reason;
    }

    @Override
    public String signedDownloadUrl(String key, Duration ttl, String downloadFilename) {
        throw new StorageUnavailableException(reason);
    }
}
