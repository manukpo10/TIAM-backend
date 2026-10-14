package com.tiam.pack.storage;

/** The file store cannot serve links (not configured, or configured wrongly). */
public class StorageUnavailableException extends RuntimeException {

    public StorageUnavailableException(String message) {
        super(message);
    }
}
