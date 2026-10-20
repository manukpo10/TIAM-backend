package com.tiam.common.email;

import java.util.Optional;
import java.util.regex.Pattern;

/** Log-safe rendering of email addresses (the production log level is INFO and logs are PII), and a strict address check. */
public final class EmailAddresses {

    /**
     * A plain address, nothing else: no display name, no angle brackets, no quotes, no list, no whitespace or control
     * character. Whatever passes this can be put in an email header without being able to add another header.
     */
    private static final Pattern PLAIN = Pattern.compile(
            "[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
    private static final int MAX_LENGTH = 254;

    private EmailAddresses() {
    }

    /**
     * The address trimmed, if (and only if) it is a plain email address such as {@code owner@gmail.com}. Used before an
     * address reaches a header (Reply-To), where a CR or LF would be a header injection.
     */
    public static Optional<String> plainAddress(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String address = raw.strip();
        if (address.isEmpty() || address.length() > MAX_LENGTH || !PLAIN.matcher(address).matches()) {
            return Optional.empty();
        }
        return Optional.of(address);
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
