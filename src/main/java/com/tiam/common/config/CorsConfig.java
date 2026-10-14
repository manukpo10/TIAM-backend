package com.tiam.common.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private static final String CHECKOUT_PATH = "/packs/checkout";

    @Value("${tiam.cors.allowed-origins:http://localhost:5173}")
    private String allowedOriginsRaw;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        var origins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return request -> isCheckoutFormPost(request) ? null : source.getCorsConfiguration(request);
    }

    /**
     * The pack checkout is a plain HTML form post (a full-page navigation), not a fetch: CORS is the wrong
     * tool for it, and Spring's CORS filter answers a post from an unlisted origin (apex vs www, a Vercel preview)
     * with a bare "403 Invalid CORS request" page. Returning no CORS configuration for it lets the request reach
     * PackCheckoutController, whose PackOriginPolicy applies the same origin list and answers with a redirect
     * back to the landing ({@code ?error=origen}) that the page can explain. Every other endpoint keeps the
     * strict list.
     */
    private static boolean isCheckoutFormPost(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && (request.getContextPath() + CHECKOUT_PATH).equals(request.getRequestURI());
    }
}
