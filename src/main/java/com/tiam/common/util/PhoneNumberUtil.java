package com.tiam.common.util;

/**
 * Argentina-specific phone-number normalization. WhatsApp's inbound webhook
 * {@code from} field is documented to sometimes omit the "9" mobile-number
 * indicator that the Send API requires when messaging TO that same number —
 * {@link #normalize} strips it for consistent matching, {@link #toWhatsAppSendFormat}
 * re-adds it for sending.
 *
 * <p>Known gap: numbers entered in the legacy "15" local-mobile-dialing format
 * (e.g. "011 15-2233-4455") do not normalize to the same key as their modern
 * equivalent ("11 2233-4455") — the "15" infix position depends on the area
 * code's length, which would need a full area-code table to resolve safely.
 */
public final class PhoneNumberUtil {

    private static final String COUNTRY_CODE = "54";
    private static final String MOBILE_INDICATOR = "9";
    // 54 + 10-digit national number (area code + subscriber number is always
    // 10 digits total across Argentina's numbering plan, regardless of how
    // that's split). Anything shorter is too implausible to be a real number —
    // reject it instead of silently producing a short, collision-prone key.
    private static final int MIN_LENGTH = 12;

    private PhoneNumberUtil() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String digits = raw.replaceAll("[^0-9]", "");

        if (digits.startsWith("0")) {
            digits = digits.substring(1);
        }

        if (!digits.startsWith(COUNTRY_CODE)) {
            digits = COUNTRY_CODE + digits;
        }

        if (digits.startsWith(MOBILE_INDICATOR, COUNTRY_CODE.length())) {
            digits = digits.substring(0, COUNTRY_CODE.length())
                    + digits.substring(COUNTRY_CODE.length() + 1);
        }

        if (digits.length() < MIN_LENGTH) {
            return "";
        }

        return digits;
    }

    public static String toWhatsAppSendFormat(String normalized) {
        if (normalized == null || !normalized.startsWith(COUNTRY_CODE)) {
            throw new IllegalArgumentException(
                    "Expected a normalized Argentine phone number starting with \"54\": " + normalized);
        }
        return normalized.substring(0, COUNTRY_CODE.length())
                + MOBILE_INDICATOR
                + normalized.substring(COUNTRY_CODE.length());
    }
}
