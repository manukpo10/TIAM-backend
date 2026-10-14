package com.tiam.pack.service;

import com.mercadopago.resources.payment.Payment;
import java.math.BigDecimal;

/**
 * What the pack code needs to know about a Mercado Pago payment, copied out of the SDK object so the
 * business rules (amount, currency, status) are testable without the SDK and cannot be handed a
 * half-built one.
 *
 * @param paymentId         Mercado Pago's payment id (the unique-per-order key)
 * @param status            approved, rejected, cancelled, refunded, charged_back, pending, in_process...
 * @param amount            {@code transaction_amount}: what the SELLER is paid. Not {@code total_paid_amount},
 *                          which includes the buyer's financing interest.
 * @param currency          {@code currency_id}
 * @param externalReference {@code pack:<order id>}
 */
public record PaymentSnapshot(String paymentId, String status, BigDecimal amount, String currency,
        String externalReference) {

    public static PaymentSnapshot from(String paymentId, Payment payment) {
        return new PaymentSnapshot(paymentId, payment.getStatus(), payment.getTransactionAmount(),
                payment.getCurrencyId(), payment.getExternalReference());
    }
}
