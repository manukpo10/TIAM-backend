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

    /**
     * http or https to the developer's own machine ({@code localhost}, {@code 127.0.0.1} or {@code [::1]}, any port).
     * Used ONLY when the dev mode is active, to let a local test open the links of its own emails; it is never a
     * substitute for {@link #isPublicHttps}.
     */
    public static boolean isLoopbackHttp(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = new URI(url.strip());
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (host == null || uri.getUserInfo() != null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("[::1]") || lower.equals("::1");
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
