package com.tiam.pack.service;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.email.EmailAddresses;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Does the actual work of "send me my access again", off the request thread (see {@link PackAccessResendService}
 * for why): finds the buyer's most recent paid order, rotates its token (the old link may have leaked), renews
 * the access window and emails the new link.
 *
 * <p>Order of operations protects the buyer: nothing is rotated unless a real email provider is live and the
 * downloads base is usable; and if the provider then fails to take the email, the rotation is undone so the
 * old link keeps working instead of leaving the buyer with a dead link and an email they never got.
 *
 * <p>Not transactional: it sends email, which must never hold a database connection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackAccessResender {

    private final PackOrderRepository orders;
    private final PackOrderService packOrderService;
    private final PackAccessEmailComposer composer;
    private final EmailSender emailSender;
    private final ChallengePurchaseService challengePurchases;

    @Async
    public void resendAsync(String email) {
        resendNow(email);
    }

    /** @return true if a new link was emailed; false for any other outcome (all of them silent to the caller) */
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

        Long orderId = paid.get(0).getId();
        Optional<PackOrderService.AccessRotation> rotated = packOrderService.rotateAccess(orderId);
        if (rotated.isEmpty()) {
            return false; // stopped being PAID between the lookup and the lock (e.g. refunded)
        }
        PackOrder order = rotated.get().order();

        Optional<String> gamesLink = order.isWithGames()
                ? challengePurchases.findAccessLinkIfActive(order.getChallengePurchaseId())
                : Optional.empty();
        EmailMessage message = composer.compose(order, gamesLink, PackAccessEmailComposer.Variant.RESEND);

        boolean delivered;
        try {
            delivered = emailSender.send(message);
        } catch (RuntimeException e) {
            log.error("Pack access re-send: the email provider threw: {}", e.getMessage(), e);
            delivered = false;
        }
        if (!delivered) {
            packOrderService.restoreAccess(orderId, order.getAccessToken(),
                    rotated.get().previousToken(), rotated.get().previousExpiry());
            log.warn("Pack access re-send for order id={} was NOT delivered; the previous link was restored", orderId);
            return false;
        }
        // If the very first email never made it, this one now covers it: settle that bookkeeping (a no-op when
        // already set) so the retry job cannot send a late, redundant first email later.
        orders.markEmailSent(orderId, Instant.now());
        log.info("Pack access re-sent for order id={} to {}", orderId, EmailAddresses.mask(email));
        return true;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
