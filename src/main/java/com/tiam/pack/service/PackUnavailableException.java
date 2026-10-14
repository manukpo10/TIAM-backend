package com.tiam.pack.service;

/** Something outside our control (Mercado Pago, the file store) is unavailable right now (HTTP 503): try again. */
public class PackUnavailableException extends RuntimeException {

    public PackUnavailableException(String message) {
        super(message);
    }

    public PackUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
