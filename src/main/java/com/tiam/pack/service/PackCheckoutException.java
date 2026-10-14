package com.tiam.pack.service;

/** A checkout that cannot continue; the controller turns it into a redirect with {@code ?error=<code>}. */
public class PackCheckoutException extends RuntimeException {

    private final PackCheckoutError error;

    public PackCheckoutException(PackCheckoutError error) {
        super(error.code());
        this.error = error;
    }

    public PackCheckoutException(PackCheckoutError error, String detail) {
        super(error.code() + ": " + detail);
        this.error = error;
    }

    public PackCheckoutError error() {
        return error;
    }
}
