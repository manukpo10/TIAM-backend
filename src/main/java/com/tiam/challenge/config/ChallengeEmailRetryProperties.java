package com.tiam.challenge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the access-email retry job (see
 * {@link com.tiam.challenge.service.ChallengeAccessEmailRetryService}), which re-sends the
 * "Tu Desafío está listo" email to paid purchases that never got it.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.challenge.email-retry")
public class ChallengeEmailRetryProperties {

    /** Master switch — when false, the scheduled job is a no-op. */
    private boolean enabled = true;

    /** Delay between successive runs, in milliseconds (15 minutes, like the reconciliation job). */
    private long intervalMs = 900_000L;

    /**
     * Purchases paid more recently than this many minutes ago are left to the immediate
     * after-payment send, so the two never race on the same purchase in the normal case.
     */
    private long minAgeMinutes = 5L;

    /**
     * Purchases paid longer ago than this many hours are given up on: a link emailed days
     * later is more confusing than helpful, and the buyer still has WhatsApp.
     */
    private long lookbackHours = 72L;
}
