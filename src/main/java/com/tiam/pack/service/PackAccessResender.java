package com.tiam.pack.service;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.email.EmailAddresses;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Does the actual work of "send me my access again", off the request thread (see {@link PackAccessResendService}
 * for why): finds ALL the buyer's paid orders, gives each a replacement token and emails every link in ONE message.
 *
 * <p><b>Nothing the buyer already has is invalidated.</b> The request is public and unauthenticated, so anyone who
 * knows an email can trigger it. A replacement token waits next to the working one and only takes over when the
 * buyer opens it (see {@link PackOrderService#issueReplacementToken}); asked again within a day the same token is
 * re-sent. So there is nothing to undo when the provider refuses the message, and no way for a stranger to lock a
 * buyer out.
 *
 * <p><b>The per-email quota is spent only for emails that really have a paid order</b>, after the lookup: an email
 * with no purchase leaves no trace anywhere (the limiter included), and cannot be used to fill it.
 *
 * <p>Order of checks protects the buyer too: nothing is issued unless a real email provider is live and the
 * downloads base is usable. Not transactional: it sends email, which must never hold a database connection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackAccessResender {

    private static final Duration IDEMPOTENCY_BUCKET = Duration.ofMinutes(10);

    private final PackOrderRepository orders;
    private final PackOrderService packOrderService;
    private final PackAccessEmailComposer composer;
    private final EmailSender emailSender;
    private final ChallengePurchaseService challengePurchases;
    private final PackRateLimiter rateLimiter;
    private final PackProperties properties;
    private final Clock clock;

    @Async
    public void resendAsync(String email) {
        resendNow(email);
    }

    /** @return true if a message with the links was emailed; false for any other outcome (all silent to the caller) */
    public boolean resendNow(String rawEmail) {
        try {
            return doResend(normalize(rawEmail));
        } catch (Exception e) {
            log.error("Pack access re-send failed: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean doResend(String email) {
        List<PackOrder> paid = orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(email, PackOrderStatus.PAID);
        if (paid.isEmpty()) {
            log.info("Pack access re-send: no paid order for {}", EmailAddresses.mask(email));
            return false;
        }
        if (!emailSender.isEnabled()) {
            log.warn("Pack access re-send skipped: no email provider is configured (RESEND_API_KEY / EMAIL_FROM)");
            return false;
        }
        if (!composer.hasPublicDownloadBase()) {
            log.error("Pack access re-send skipped: PACK_DOWNLOAD_BASE_URL is \"{}\" (empty, localhost or not https)",
                    composer.downloadBase());
            return false;
        }
        if (!rateLimiter.tryAcquire("resend-email:" + email, properties.getRateLimits().getResendPerEmail(), Duration.ofDays(1))) {
            log.warn("Pack access re-send over the email quota ({}); dropped silently", EmailAddresses.mask(email));
            return false;
        }

        List<PackAccessEmailComposer.ResendEntry> entries = new ArrayList<>();
        for (PackOrder candidate : paid) {
            packOrderService.issueReplacementToken(candidate.getId()).ifPresent(issued -> {
                PackOrder order = issued.order();
                Optional<String> gamesLink = order.isWithGames()
                        ? challengePurchases.findAccessLinkIfActive(order.getChallengePurchaseId())
                        : Optional.empty();
                entries.add(new PackAccessEmailComposer.ResendEntry(order, issued.token(), gamesLink));
            });
        }
        if (entries.isEmpty()) {
            return false; // every order stopped being PAID between the lookup and the lock (e.g. refunded)
        }

        EmailMessage message = composer.composeResend(email, entries, idempotencyKey(entries));
        boolean delivered;
        try {
            delivered = emailSender.send(message);
        } catch (RuntimeException e) {
            log.error("Pack access re-send: the email provider threw: {}", e.getMessage(), e);
            delivered = false;
        }
        if (!delivered) {
            log.warn("Pack access re-send to {} was NOT delivered; the links the buyer already has are untouched",
                    EmailAddresses.mask(email));
            return false;
        }
        // If a first email never made it, this one now covers it: settle that bookkeeping (a no-op when already set)
        // so the retry job cannot send a late, redundant first email later.
        entries.forEach(entry -> orders.markEmailSent(entry.order().getId(), clock.instant()));
        log.info("Pack access re-sent for {} order(s) to {}", entries.size(), EmailAddresses.mask(email));
        return true;
    }

    /** Distinct per request (the provider drops a repeated key), yet stable for a double fire within minutes. */
    private String idempotencyKey(List<PackAccessEmailComposer.ResendEntry> entries) {
        long bucket = clock.millis() / IDEMPOTENCY_BUCKET.toMillis();
        return "pack-access-resend/" + entries.get(0).order().getId() + "/" + entries.size() + "/" + bucket;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
