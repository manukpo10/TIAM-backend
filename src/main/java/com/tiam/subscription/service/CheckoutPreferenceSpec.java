package com.tiam.subscription.service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Everything a one-time multi-item checkout needs, provider-neutral so callers do not touch the SDK.
 *
 * @param payerName           full name; split into first name and surname for Mercado Pago (helps its fraud review)
 * @param backUrl             where the buyer returns after paying (success, failure and pending alike)
 * @param statementDescriptor text on the buyer's card statement
 */
public record CheckoutPreferenceSpec(
        List<CheckoutLine> lines,
        String payerEmail,
        String payerName,
        String externalReference,
        String backUrl,
        OffsetDateTime expiresFrom,
        OffsetDateTime expiresAt,
        String statementDescriptor) {
}
