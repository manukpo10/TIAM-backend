package com.tiam.pack.service;

import java.util.List;

/**
 * What the checkout form sent, untrusted and un-normalised. There is deliberately no price field: the
 * only money-related input is {@code expectedTotal}, which is compared against the server price and
 * never used to charge.
 *
 * @param edition       "familias" or "profesionales"
 * @param extras        the repeated {@code extras} field ("juegos", "cuadernillo")
 * @param expectedTotal the total the page displayed, to catch a stale page; may be null
 * @param clientIp      the caller's IP as the server sees it, for rate limiting only
 */
public record CheckoutCommand(
        String email,
        String name,
        String edition,
        List<String> extras,
        String phone,
        String expectedTotal,
        String utmSource,
        String utmMedium,
        String utmCampaign,
        String utmContent,
        String clientIp) {
}
