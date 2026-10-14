package com.tiam.pack.service;

/** A public pack endpoint was called too often from one client (HTTP 429). */
public class PackRateLimitedException extends RuntimeException {

    public PackRateLimitedException() {
        super("Too many requests");
    }
}
