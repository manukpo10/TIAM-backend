package com.tiam.pack.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** URL sanity checks shared by the startup report and the sales-readiness gate. */
public final class PackUrls {

    private PackUrls() {
    }

    /** https, a host, and not a loopback / wildcard address: something Mercado Pago and a buyer's phone can open. */
    public static boolean isPublicHttps(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = new URI(url.strip());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return !(lower.equals("localhost") || lower.endsWith(".localhost") || lower.startsWith("127.")
                    || lower.equals("0.0.0.0") || lower.equals("[::1]") || lower.equals("::1")
                    || lower.endsWith(".local") || lower.endsWith(".internal"));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
