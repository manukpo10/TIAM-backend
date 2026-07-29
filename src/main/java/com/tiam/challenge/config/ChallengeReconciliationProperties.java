package com.tiam.challenge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the challenge-purchase reconciliation job (see
 * {@link com.tiam.challenge.service.ChallengePurchaseReconciliationService}), which
 * recovers PENDING purchases whose Mercado Pago webhook notification was never
 * delivered.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.challenge.reconciliation")
public class ChallengeReconciliationProperties {

    /** Master switch — when false, the scheduled job is a no-op. */
    private boolean enabled = true;

    /** Delay between successive runs, in milliseconds. */
    private long intervalMs = 900_000L; // 15 minutes

    /**
     * Purchases created more recently than this many minutes ago are left alone —
     * gives an in-progress checkout (and MP's own webhook retries) time to complete
     * before reconciliation second-guesses it.
     */
    private long gracePeriodMinutes = 10L;

    /**
     * Purchases created longer ago than this many days are no longer polled.
     *
     * <p>Default is 30 — deliberately NOT a short "abandoned cart" window. Checkout Pro
     * (see {@code MercadoPagoService.createPreference}) sets no {@code payment_methods}
     * exclusions, so it offers every method enabled on the merchant account, including
     * cash coupons (Rapipago / Pago Fácil) common in Argentina. Those are paid in
     * person and can confirm with MP several days after the purchase row was created.
     * A shorter lookback would let reconciliation give up on — and permanently strand —
     * a customer who genuinely paid via cash coupon before their webhook notification
     * arrived (or was lost). Do NOT lower this without first confirming cash-coupon
     * payment methods are excluded at checkout.
     */
    private long lookbackDays = 30L;
}
