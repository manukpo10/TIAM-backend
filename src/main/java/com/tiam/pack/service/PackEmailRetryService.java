package com.tiam.pack.service;

import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Safety net for the after-payment access email, the pack twin of the Desafío's retry job. The immediate send
 * is a single best-effort attempt; every 15 minutes this job retries what it left undelivered: PAID within the
 * last 72 hours, with {@code email_sent_at} still NULL. It covers a restart between commit and send, a
 * provider 5xx/429/timeout, an unverified sender domain, and {@code RESEND_API_KEY} being set after the first
 * sales (the first run after the deploy sends them).
 *
 * <p>A retry cannot double-send: it goes through {@link PackOrderEmailService#sendAccessEmail}, which skips
 * anything with {@code email_sent_at} set, records success atomically, and uses a stable provider
 * idempotency key. Orders paid in the last few minutes are left to the immediate send so the two do not overlap.
 * {@code fixedDelay} guarantees the runs never overlap each other.
 *
 * <p>Single-instance assumption, as for the other jobs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackEmailRetryService {

    private final PackOrderRepository orders;
    private final PackOrderEmailService emailService;
    private final PackProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${tiam.pack.email-retry.interval-ms:900000}")
    public void retryUndeliveredAccessEmails() {
        try {
            doRetry();
        } catch (Exception e) {
            log.error("Pack access email retry run failed: {}", e.getMessage(), e);
        }
    }

    private void doRetry() {
        PackProperties.EmailRetry settings = properties.getEmailRetry();
        if (!settings.isEnabled()) {
            log.debug("Pack access email retry is disabled (tiam.pack.email-retry.enabled=false) - skipping");
            return;
        }
        if (!emailService.isDeliveryEnabled()) {
            log.debug("Pack access email retry skipped - no email provider configured");
            return;
        }
        if (emailService.isBlockedByUnusableLinkBase()) {
            log.error("Pack access email retry skipped - PACK_DOWNLOAD_BASE_URL is empty, localhost or not https; "
                    + "no email would carry a usable link. Fix it within the 72h window and the retry will deliver.");
            return;
        }

        Instant now = clock.instant();
        Instant since = now.minus(settings.getLookbackHours(), ChronoUnit.HOURS);
        Instant until = now.minus(settings.getMinAgeMinutes(), ChronoUnit.MINUTES);

        List<Long> candidates = orders.findIdsAwaitingAccessEmail(PackOrderStatus.PAID, since, until);
        if (candidates.isEmpty()) {
            return;
        }

        log.info("Pack access email retry: {} paid order(s) still without their access email", candidates.size());
        int delivered = 0;
        for (Long orderId : candidates) {
            try {
                if (emailService.sendAccessEmail(orderId)) {
                    delivered++;
                }
            } catch (Exception e) {
                log.error("Pack access email retry failed for order id={}: {}", orderId, e.getMessage(), e);
            }
        }
        log.info("Pack access email retry: delivered {} of {}", delivered, candidates.size());
    }
}
