package com.tiam.pack.domain;

import java.util.OptionalLong;

/** The {@code external_reference} Mercado Pago carries for a pack order: {@code pack:<order id>}. */
public final class PackExternalReference {

    public static final String PREFIX = "pack:";

    private PackExternalReference() {
    }

    public static String of(long orderId) {
        return PREFIX + orderId;
    }

    public static boolean isPackReference(String reference) {
        return reference != null && reference.startsWith(PREFIX);
    }

    /** The order id inside a pack reference, or empty if it is not one (or the id is not a positive number). */
    public static OptionalLong parse(String reference) {
        if (!isPackReference(reference)) {
            return OptionalLong.empty();
        }
        String digits = reference.substring(PREFIX.length());
        if (digits.isEmpty() || digits.length() > 18 || !digits.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        long id = Long.parseLong(digits);
        return id > 0 ? OptionalLong.of(id) : OptionalLong.empty();
    }
}
