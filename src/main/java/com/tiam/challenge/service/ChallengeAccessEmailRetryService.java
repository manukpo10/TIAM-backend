package com.tiam.challenge.service;

import com.tiam.challenge.config.ChallengeEmailRetryProperties;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Safety net for the after-payment access email, the same way
 * {@link ChallengePurchaseReconciliationService} is one for the Mercado Pago webhook.
 *
 * <p>The immediate send is a single best-effort attempt. This job, every 15 minutes, retries
 * the purchases it left undelivered: paid within the last 72 hours, with a buyer email and
 * {@code email_sent_at} still NULL. That covers a restart between commit and send, a
 * Resend 5xx/429/timeout, a not-yet-verified domain, and {@code RESEND_API_KEY} being set
 * after the first payments (the first run after the deploy sends them).
 *
 * <p><b>Why a retry cannot double-send:</b> it goes through
 * {@link ChallengePurchaseEmailService#sendAccessEmail}, which skips anything with
 * {@code email_sent_at} set, records success with an atomic conditional update, and sends
 * with a stable provider idempotency key ({@code challenge-access/<id>}). Purchases younger
 * than {@code min-age-minutes} are left to the immediate send so the two do not overlap.
 * {@code fixedDelay} guarantees runs of this job never overlap each other.
 *
 * <p>Single-instance assumption, same as the reconciliation job: with several instances the
 * idempotency key and the atomic update still prevent a duplicate, but the same purchase
 * could be attempted twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeAccessEmailRetryService {

    private final ChallengePurchaseRepository challengePurchaseRepository;
    private final ChallengePurchaseEmailService emailService;
    private final ChallengeEmailRetryProperties properties;

    @Scheduled(fixedDelayString = "${tiam.challenge.email-retry.interval-ms:900000}")
    public void retryUndeliveredAccessEmails() {
        try {
            doRetry();
        } catch (Exception e) {
            log.error("Access email retry run failed: {}", e.getMessage(), e);
        }
    }

    private void doRetry() {
        if (!properties.isEnabled()) {
            log.debug("Access email retry is disabled (tiam.challenge.email-retry.enabled=false) — skipping");
            return;
        }
        if (!emailService.isDeliveryEnabled()) {
            // Logging fallback: nothing could be delivered, and the purchases stay eligible
            // for 72h so they are sent as soon as RESEND_API_KEY / EMAIL_FROM are set.
            log.debug("Access email retry skipped — no email provider configured");
            return;
        }
        if (emailService.isBlockedByUnusableLinkBase()) {
            log.error("Access email retry skipped — DESAFIO_PLAY_BASE_URL is empty, localhost or not https; "
                    + "no email would carry a usable link. Fix it within the 72h window and the retry will deliver.");
            return;
        }

        Instant now = Instant.now();
        Instant since = now.minus(properties.getLookbackHours(), ChronoUnit.HOURS);
        Instant until = now.minus(properties.getMinAgeMinutes(), ChronoUnit.MINUTES);

        List<Long> candidates = challengePurchaseRepository.findIdsAwaitingAccessEmail(
                ChallengePurchaseStatus.PAID, since, until);
        if (candidates.isEmpty()) {
            return;
        }

        log.info("Access email retry: {} paid purchase(s) still without their access email", candidates.size());
        int delivered = 0;
        for (Long purchaseId : candidates) {
            try {
                if (emailService.sendAccessEmail(purchaseId)) {
                    delivered++;
                }
            } catch (Exception e) {
                // One bad purchase must not stop the others — log and move on.
                log.error("Access email retry failed for challenge purchase id={}: {}",
                        purchaseId, e.getMessage(), e);
            }
        }
        log.info("Access email retry: delivered {} of {}", delivered, candidates.size());
    }
}
