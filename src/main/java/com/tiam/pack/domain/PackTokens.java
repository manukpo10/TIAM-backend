package com.tiam.pack.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Order access tokens: 256 random bits as unpadded base64url. The token IS the credential. */
public final class PackTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private PackTokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Cheap shape check so garbage never reaches the database. */
    public static boolean looksValid(String token) {
        return token != null && SHAPE.matcher(token).matches();
    }

    /** Log-safe form: a token in the logs is a credential in the logs. */
    public static String mask(String token) {
        if (token == null || token.isBlank()) {
            return "<none>";
        }
        return token.substring(0, Math.min(4, token.length())) + "...";
    }
}
