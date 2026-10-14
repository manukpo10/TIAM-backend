package com.tiam.pack.service;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.email.EmailAddresses;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderPaidEvent;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import java.time.Clock;
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
 * Sends the buyer their downloads link by email once an order is paid. ONE email, with no attachments.
 *
 * <p><b>Send-once.</b> Same three layers as the Desafío email: the event is published only on the real
 * transition to PAID (under the row lock); {@code email_sent_at} is checked and then written with an atomic
 * conditional update; and the provider idempotency key ({@code pack-access/<id>}) makes the provider itself
 * drop a repeat inside its window. {@code email_sent_at} is written only AFTER the provider accepted the
 * message, so NULL on a PAID order means "not delivered".
 *
 * <p><b>Recovery.</b> This listener is a single best-effort attempt; anything it cannot deliver is picked up
 * by {@link PackEmailRetryService}, which calls {@link #sendAccessEmail} again. The layers above make that safe.
 *
 * <p><b>After commit and async.</b> The listener runs after the payment transaction commits, so nobody is
 * emailed for a payment that rolls back, and on another thread, so neither the webhook nor the buyer's
 * return page waits for the provider.
 *
 * <p>Nothing here may break the payment flow: every failure is logged and swallowed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackOrderEmailService {

    private final PackOrderRepository orders;
    private final PackAccessEmailComposer composer;
    private final EmailSender emailSender;
    private final ChallengePurchaseService challengePurchases;
    private final Clock clock;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaid(PackOrderPaidEvent event) {
        try {
            sendAccessEmail(event.orderId());
        } catch (Exception e) {
            log.error("Unexpected error sending the access email for pack order id={}: {}",
                    event.orderId(), e.getMessage(), e);
        }
    }

    /** True when a real provider is configured (false = the logging fallback, nothing can be delivered). */
    public boolean isDeliveryEnabled() {
        return emailSender.isEnabled();
    }

    /**
     * True when a real provider is live but the downloads base (PACK_DOWNLOAD_BASE_URL) is empty, localhost
     * or not https: every email would carry a link the buyer cannot open, so sending is held back.
     */
    public boolean isBlockedByUnusableLinkBase() {
        return emailSender.isEnabled() && !composer.hasPublicDownloadBase();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkLinkBaseAtStartup() {
        if (isBlockedByUnusableLinkBase()) {
            log.error("PACK_DOWNLOAD_BASE_URL is \"{}\" (empty, localhost or not https) while email delivery is ON: "
                    + "pack access emails will NOT be sent until it is a public https URL such as "
                    + "https://tiamdigital.com/pack/descargas. Undelivered orders are retried automatically "
                    + "for 72h after payment once it is fixed.", composer.downloadBase());
        }
    }

    /**
     * Sends the access email for an order unless it must not be sent. Safe to call repeatedly for the same
     * order (retry job, listener, a re-run after a crash).
     *
     * @return true if the provider accepted the email and it was recorded
     */
    public boolean sendAccessEmail(Long orderId) {
        Optional<PackOrder> found = orders.findById(orderId);
        if (found.isEmpty()) {
            log.warn("Pack access email skipped: order id={} not found", orderId);
            return false;
        }
        PackOrder order = found.get();

        if (order.getStatus() != PackOrderStatus.PAID) {
            log.warn("Pack access email skipped: order id={} is {}, not PAID", orderId, order.getStatus());
            return false;
        }
        if (order.getEmailSentAt() != null) {
            log.info("Pack access email skipped: order id={} was already emailed at {}", orderId, order.getEmailSentAt());
            return false;
        }
        if (!StringUtils.hasText(order.getEmail())) {
            log.warn("Pack access email skipped: order id={} has no buyer email", orderId);
            return false;
        }
        if (isBlockedByUnusableLinkBase()) {
            // Not sent and email_sent_at untouched, so the retry job delivers it once the config is fixed.
            log.error("Pack access email for order id={} NOT sent: PACK_DOWNLOAD_BASE_URL is \"{}\" "
                    + "(empty, localhost or not https), so the link would be unusable", orderId, composer.downloadBase());
            return false;
        }

        Optional<String> gamesLink = order.isWithGames()
                ? challengePurchases.findAccessLinkIfActive(order.getChallengePurchaseId())
                : Optional.empty();
        EmailMessage message = composer.compose(order, gamesLink, PackAccessEmailComposer.Variant.FIRST);
        if (!emailSender.send(message)) {
            log.warn("Pack access email for order id={} was NOT delivered (email_sent_at stays NULL; "
                    + "see the log lines above for the reason)", orderId);
            return false;
        }

        int recorded = orders.markEmailSent(orderId, clock.instant());
        if (recorded == 0) {
            log.warn("Pack access email for order id={} was delivered but email_sent_at was already set by someone else",
                    orderId);
        }
        log.info("Pack access email delivered for order id={} to {}", orderId, EmailAddresses.mask(order.getEmail()));
        return true;
    }
}
