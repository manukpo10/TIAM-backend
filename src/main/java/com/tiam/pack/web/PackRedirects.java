package com.tiam.pack.web;

import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.service.PackCheckoutError;
import java.net.URI;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds every URL the checkout form can be redirected to. The destination is NEVER taken from the
 * request: the landing page is derived from the edition (already reduced to a known value) and the
 * configured landing origin, and the payment URL must belong to Mercado Pago. That is what rules out
 * an open redirect through this endpoint.
 */
@Component
@RequiredArgsConstructor
public class PackRedirects {

    private static final PackEdition DEFAULT_EDITION = PackEdition.FAMILIAS;

    private final PackProperties properties;

    /**
     * {@code <landing>/pack/<edition>[?error=<code>]#comprar}, falling back to the families landing. The Cuadernos
     * have a top-level page of their own: {@code <landing>/cuadernos[?error=<code>]#comprar}.
     */
    public String landing(String editionSlug, PackCheckoutError error) {
        PackEdition edition = PackEdition.fromSlug(editionSlug).orElse(DEFAULT_EDITION);
        String base = stripTrailingSlashes(properties.getLandingBaseUrl());
        String query = error == null ? "" : "?error=" + error.code();
        String path = edition.isCuadernos() ? "/cuadernos" : "/pack/" + edition.slug();
        return base + path + query + "#comprar";
    }

    /** True only for https URLs on a Mercado Pago / Mercado Libre domain (production and sandbox checkouts). */
    public static boolean isMercadoPagoUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                return false;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return isOrSubdomainOf(host, "mercadopago.com") || isOrSubdomainOf(host, "mercadopago.com.ar")
                    || isOrSubdomainOf(host, "mercadolibre.com") || isOrSubdomainOf(host, "mercadolibre.com.ar");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isOrSubdomainOf(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String stripTrailingSlashes(String url) {
        String result = url == null ? "" : url.strip();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
