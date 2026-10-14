package com.tiam.pack.service;

/** The order reached its anti-sharing cap on issued download links (HTTP 429). */
public class PackDownloadLimitException extends RuntimeException {

    /** Which cap was hit. */
    public enum Cap {
        DAILY,
        TOTAL
    }

    private final Cap cap;

    public PackDownloadLimitException(Cap cap) {
        super("Download limit reached: " + cap);
        this.cap = cap;
    }

    public Cap cap() {
        return cap;
    }
}
