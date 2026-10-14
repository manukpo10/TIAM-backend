package com.tiam.pack.web;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether a browser form post may come from the origin it claims. The allowed origins are the
 * ones already in {@code CORS_ALLOWED_ORIGINS} (the public site). A POST with no Origin header (older
 * browsers, same-origin tools) is accepted: this is defence in depth on top of the CORS filter, and
 * the worst a forged checkout can do is create a PENDING order nobody pays.
 */
@Component
public class PackOriginPolicy {

    private final Set<String> allowedOrigins;

    public PackOriginPolicy(@Value("${tiam.cors.allowed-origins:http://localhost:5173}") String allowedOriginsCsv) {
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(PackOriginPolicy::normalize)
                .filter(origin -> !origin.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        return allowedOrigins.contains(normalize(origin));
    }

    private static String normalize(String origin) {
        String result = origin.strip().toLowerCase(Locale.ROOT);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
