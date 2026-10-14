package com.tiam.pack.service;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.email.EmailAddresses;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.common.exception.BadRequestException;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackRegretRequest;
import com.tiam.pack.dto.RegretReceiptResponse;
import com.tiam.pack.dto.RegretRequest;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.pack.repository.PackRegretRequestRepository;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The "botón de arrepentimiento": records the request, hands the buyer a receipt (reference + the same
 * information by email, as a constancia) and tells the owner. The refund itself is the owner's decision,
 * made in Mercado Pago; the resulting {@code refunded} notification revokes the access by itself.
 *
 * <p>Order of priorities: the request is stored FIRST, and emailing is best effort afterwards. A failing
 * email provider must never lose or reject a regret request, so it is logged and the buyer still gets the
 * reference on screen.
 *
 * <p><b>The form must not become a mail relay.</b> It is public and the receipt would leave from the same domain
 * that delivers purchase emails, so: (1) the receipt carries only the reference and the date, never the text the
 * sender typed; (2) it is emailed ONLY to an address that has a paid purchase (pack or Desafío), while anyone
 * else gets the same constancia on screen and nothing in their inbox; (3) the number of receipts per day is capped
 * globally, so even a flood of rotating IPs and real customers' addresses cannot become a cannon; (4) the
 * per-IP and per-email limits apply. The owner is ALWAYS told, whatever the sender. Not transactional on purpose
 * (it sends email).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackRegretService {

    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+'-]{1,64}@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}");
    /** "42", "#42", "pack:42", "TIAM-42", "tiam 42". */
    private static final Pattern ORDER_NUMBER = Pattern.compile("(?i)^\\s*(?:tiam[-\\s]?|pack:|#)?(\\d{1,18})\\s*$");

    private final PackRegretRequestRepository regrets;
    private final PackOrderRepository orders;
    private final ChallengePurchaseService challengePurchases;
    private final PackRegretEmailComposer composer;
    private final EmailSender emailSender;
    private final PackProperties properties;
    private final PackRateLimiter rateLimiter;

    public RegretReceiptResponse submit(RegretRequest request, String clientIp) {
        String email = request.email() == null ? "" : request.email().strip().toLowerCase(Locale.ROOT);
        String fullName = request.fullName() == null ? "" : request.fullName().strip().replaceAll("\\s+", " ");
        validate(email, fullName);
        enforceLimits(email, clientIp);

        PackRegretRequest entity = new PackRegretRequest();
        entity.setFullName(fullName);
        entity.setEmail(email);
        entity.setOrderRef(clean(request.orderRef(), 100, false));
        entity.setReason(clean(request.reason(), 2000, true));
        entity.setOrderId(matchingOrderId(entity.getOrderRef(), email));
        entity.setStatus("RECEIVED");
        entity = regrets.save(entity);

        String reference = String.format("ARR-%06d", entity.getId());
        log.info("Regret request {} recorded for {} (order id={})", reference, EmailAddresses.mask(email), entity.getOrderId());

        boolean knownBuyer = isKnownBuyer(email);
        if (knownBuyer && receiptAllowedToday()) {
            sendQuietly(composer.buyerReceipt(entity, reference), reference);
        } else {
            log.info("Regret request {}: no receipt emailed ({})", reference,
                    knownBuyer ? "daily receipt cap reached" : "the address has no purchase");
        }
        String ownerAddress = properties.effectiveRegretNotifyTo();
        if (StringUtils.hasText(ownerAddress)) {
            sendQuietly(composer.ownerNotice(entity, reference, ownerAddress, knownBuyer), reference);
        } else {
            log.warn("Regret request {} recorded but nobody was notified: PACK_REGRET_NOTIFY_TO is not set", reference);
        }
        return new RegretReceiptResponse(reference, entity.getCreatedAt());
    }

    /** A paid (or refunded / held) pack order, or a paid Desafío purchase, under this address. */
    private boolean isKnownBuyer(String email) {
        return orders.existsByEmailAndStatusInAndActivoTrue(email,
                List.of(PackOrderStatus.PAID, PackOrderStatus.REVOKED, PackOrderStatus.REVIEW))
                || challengePurchases.hasPaidPurchaseWithEmail(email);
    }

    private boolean receiptAllowedToday() {
        return rateLimiter.tryAcquire("regret-receipts-global", properties.getRateLimits().getRegretReceiptsPerDay(),
                Duration.ofDays(1));
    }

    private void validate(String email, String fullName) {
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            throw new BadRequestException("El email no es válido");
        }
        boolean hasLetter = fullName.chars().anyMatch(Character::isLetter);
        boolean forbidden = fullName.chars().anyMatch(c -> Character.isISOControl(c) || c == '<' || c == '>');
        if (fullName.length() < 2 || fullName.length() > 255 || !hasLetter || forbidden) {
            throw new BadRequestException("El nombre no es válido");
        }
    }

    private void enforceLimits(String email, String clientIp) {
        PackProperties.RateLimits limits = properties.getRateLimits();
        // IP first and bail out: a blocked visitor must not also create per-email entries in the limiter.
        if (!rateLimiter.tryAcquireByIp("regret-ip", clientIp, limits.getRegretPerIp(), Duration.ofHours(1))
                || !rateLimiter.tryAcquire("regret-email:" + email, limits.getRegretPerEmail(), Duration.ofDays(1))) {
            log.warn("Regret form over quota (ip={} email={})", clientIp, EmailAddresses.mask(email));
            throw new PackRateLimitedException();
        }
    }

    /** The order id the buyer pointed at, but ONLY if that order was bought with this same email. */
    private Long matchingOrderId(String orderRef, String email) {
        if (orderRef == null) {
            return null;
        }
        Matcher matcher = ORDER_NUMBER.matcher(orderRef);
        if (!matcher.matches()) {
            return null;
        }
        return orders.findById(Long.parseLong(matcher.group(1)))
                .filter(order -> email.equalsIgnoreCase(order.getEmail()))
                .map(PackOrder::getId)
                .orElse(null);
    }

    private void sendQuietly(EmailMessage message, String reference) {
        try {
            if (!emailSender.send(message)) {
                log.warn("Regret request {}: an email to {} was not delivered", reference, EmailAddresses.mask(message.to()));
            }
        } catch (RuntimeException e) {
            log.error("Regret request {}: email to {} failed: {}", reference, EmailAddresses.mask(message.to()), e.getMessage(), e);
        }
    }

    /** Strips control characters (keeping line breaks when asked), trims and bounds the length; blank becomes null. */
    private static String clean(String raw, int maxLength, boolean keepNewlines) {
        if (raw == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        raw.chars().forEach(c -> {
            boolean keep = !Character.isISOControl(c) || (keepNewlines && (c == '\n'));
            if (keep) {
                out.appendCodePoint(c);
            }
        });
        String cleaned = out.toString().strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
