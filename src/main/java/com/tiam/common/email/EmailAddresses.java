package com.tiam.common.email;

/** Log-safe rendering of email addresses (the production log level is INFO and logs are PII). */
public final class EmailAddresses {

    private EmailAddresses() {
    }

    /** {@code maria.gomez@gmail.com} becomes {@code m***@gmail.com}. */
    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "<none>";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
