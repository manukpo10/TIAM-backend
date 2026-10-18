package com.tiam.challenge.service;

import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchasePaidEvent;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * Sends the buyer their Desafío access link by email once a purchase is paid.
 *
 * <p><b>Send-once.</b> Three layers, from the outside in:
 * <ol>
 *   <li>{@code ChallengePurchaseService.markPaid} publishes the event only on the real
 *       PENDING/FAILED to PAID transition, under the row lock; duplicate MP webhooks and
 *       reconciliation re-runs hit the "already PAID" early return and publish nothing.</li>
 *   <li>{@code email_sent_at}: if it is already set, nothing is sent; it is written with an
 *       atomic conditional update.</li>
 *   <li>The provider idempotency key ({@code challenge-access/<id>}) makes the provider
 *       itself drop a repeat inside its 24h window.</li>
 * </ol>
 * {@code email_sent_at} is written only after the provider accepted the message, so
 * NULL on a PAID purchase with an email means "not delivered".
 *
 * <p><b>Recovery.</b> This listener is a single best-effort attempt. Anything it cannot
 * deliver (provider down or unconfigured, restart between commit and send, link base
 * misconfigured) is picked up later by {@link ChallengeAccessEmailRetryService}, which
 * calls {@link #sendAccessEmail} again; the layers above make that retry safe.
 *
 * <p><b>Why after-commit and async.</b> The listener runs after {@code markPaid}'s
 * transaction commits, so the buyer is never emailed for a payment that then rolls
 * back, and on another thread, so (a) the Mercado Pago webhook is not held up by the
 * provider's timeouts and (b) it does not inherit the already-committed transaction
 * (a repository write in a synchronous after-commit callback silently joins a
 * transaction that will never commit again).
 *
 * <p>Nothing here may break the payment flow: every failure is logged and swallowed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengePurchaseEmailService {

    private final ChallengePurchaseRepository challengePurchaseRepository;
    private final ChallengeAccessEmailComposer composer;
    private final EmailSender emailSender;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPurchasePaid(ChallengePurchasePaidEvent event) {
        try {
            sendAccessEmail(event.purchaseId());
        } catch (Exception e) {
            // Last line of defence — an uncaught exception here would only reach the async
            // executor's handler, but the contract is "log and continue".
            log.error("Unexpected error sending the access email for challenge purchase id={}: {}",
                    event.purchaseId(), e.getMessage(), e);
        }
    }

    /** True when a real provider is configured (false = the logging fallback, nothing can be delivered). */
    public boolean isDeliveryEnabled() {
        return emailSender.isEnabled();
    }

    /**
     * True when a real provider is live but the access link base (DESAFIO_PLAY_BASE_URL)
     * is empty, localhost or not https: every email would carry a link the buyer cannot
     * open, so sending is blocked until the config is fixed.
     */
    public boolean isBlockedByUnusableLinkBase() {
        return emailSender.isEnabled() && !composer.hasPublicAccessLinkBase();
    }

    /** One loud line at boot so the owner sees a bad DESAFIO_PLAY_BASE_URL before the first sale. */
    @EventListener(ApplicationReadyEvent.class)
    public void checkLinkBaseAtStartup() {
        if (isBlockedByUnusableLinkBase()) {
            log.error("DESAFIO_PLAY_BASE_URL is \"{}\" (empty, localhost or not https) while email delivery is ON: "
                    + "access emails will NOT be sent until it is a public https URL such as "
                    + "https://www.tiamdigital.com/desafio. Undelivered purchases are retried automatically "
                    + "for 72h after payment once it is fixed.", composer.accessLinkBase());
        }
    }

    /**
     * Sends the access email for a purchase unless it must not be sent. Safe to call
     * repeatedly for the same purchase (retry job, listener, a re-run after a crash).
     *
     * @return true if the provider accepted the email and it was recorded
     */
    public boolean sendAccessEmail(Long purchaseId) {
        Optional<ChallengePurchase> found = challengePurchaseRepository.findById(purchaseId);
        if (found.isEmpty()) {
            log.warn("Access email skipped: challenge purchase id={} not found", purchaseId);
            return false;
        }
        ChallengePurchase purchase = found.get();

        if (purchase.getStatus() != ChallengePurchaseStatus.PAID) {
            log.warn("Access email skipped: challenge purchase id={} is {}, not PAID",
                    purchaseId, purchase.getStatus());
            return false;
        }
        if (purchase.getEmailSentAt() != null) {
            log.info("Access email skipped: challenge purchase id={} was already emailed at {}",
                    purchaseId, purchase.getEmailSentAt());
            return false;
        }
        if (!StringUtils.hasText(purchase.getEmail())) {
            log.info("Access email skipped: challenge purchase id={} has no buyer email "
                    + "(buyer can still ask for the link on WhatsApp)", purchaseId);
            return false;
        }
        if (isBlockedByUnusableLinkBase()) {
            // Not sent and email_sent_at untouched, so the retry job delivers it once fixed.
            log.error("Access email for challenge purchase id={} NOT sent: DESAFIO_PLAY_BASE_URL is \"{}\" "
                    + "(empty, localhost or not https), so the link would be unusable",
                    purchaseId, composer.accessLinkBase());
            return false;
        }

        // One link for the whole Desafío: a month bought from the player is emailed the first purchase's link.
        EmailMessage message = purchase.getParentPurchaseId() == null
                ? composer.compose(purchase)
                : composer.compose(purchase, ChallengeLinkTokens.of(purchase, challengePurchaseRepository));
        if (!emailSender.send(message)) {
            log.warn("Access email for challenge purchase id={} was NOT delivered "
                    + "(email_sent_at stays NULL — see the log lines above for the reason)", purchaseId);
            return false;
        }

        int recorded = challengePurchaseRepository.markEmailSent(purchaseId, Instant.now());
        if (recorded == 0) {
            log.warn("Access email for challenge purchase id={} was delivered but email_sent_at "
                    + "was already set by someone else", purchaseId);
        }
        return true;
    }
}
