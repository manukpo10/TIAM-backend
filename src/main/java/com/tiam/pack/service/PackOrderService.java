package com.tiam.pack.service;

import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderPaidEvent;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.repository.PackOrderRepository;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Turns Mercado Pago payment facts into order states. Everything that can move a pack order
 * (the webhook, the reconciliation job, the buyer's return page) goes through {@link #onPayment}, so the
 * same checks apply whichever path noticed the payment first.
 *
 * <p>The rules, in order of importance:
 * <ul>
 *   <li><b>Idempotent.</b> The order is read under a row lock; PAID and REVOKED are terminal for an
 *       approved payment, and the paid event is published only on the real transition, so duplicate
 *       notifications and reconciliation re-runs deliver nothing twice.</li>
 *   <li><b>Right money or no delivery.</b> {@code transaction_amount} must equal the order total
 *       (numeric comparison) and the currency must be ARS; otherwise the order goes to REVIEW, nothing is
 *       delivered and the owner is told. The amount is never taken from the browser, and the payment
 *       itself is always re-read from Mercado Pago by the caller.</li>
 *   <li><b>Refunds close access.</b> refunded / charged_back move the order to REVOKED and close the
 *       bundled Desafío month.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackOrderService {

    private static final String CURRENCY = "ARS";

    private final PackOrderRepository orders;
    private final ChallengePurchaseService challengePurchases;
    private final ApplicationEventPublisher events;
    private final PackProperties properties;
    private final PackOwnerNotifier ownerNotifier;
    private final Clock clock;

    /**
     * Applies one payment fact to its order.
     *
     * @return the order status after applying it
     * @throws ResourceNotFoundException if there is no such order (a permanent failure: the webhook
     *         acknowledges it instead of asking Mercado Pago to retry forever)
     */
    @Transactional
    public PackOrderStatus onPayment(Long orderId, PaymentSnapshot payment) {
        PackOrder order = orders.findWithLockById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pack order not found: " + orderId));

        String status = payment.status() == null ? "" : payment.status();
        switch (status) {
            case "approved" -> onApproved(order, payment);
            case "rejected", "cancelled" -> onFailed(order);
            case "refunded", "charged_back" -> onReversed(order, payment);
            default -> log.info("Pack order id={}: MP payment {} status={} - no action taken",
                    orderId, payment.paymentId(), payment.status());
        }
        return order.getStatus();
    }

    /** The order with the replacement token that was issued (or re-issued) for it. */
    public record ReplacementToken(PackOrder order, String token) {
    }

    /** How long a replacement token is handed out again instead of minting another one. */
    private static final Duration REPLACEMENT_REUSE = Duration.ofHours(24);
    /** How long an unused replacement token stays valid. */
    private static final Duration REPLACEMENT_LIFETIME = Duration.ofDays(30);

    /**
     * Issues the token for a "send me my link again" request, WITHOUT invalidating the working one. The request
     * is public, so any stranger who knows an email can trigger it; if it revoked the buyer's link, that would be
     * a free way to lock buyers out. Instead the new token waits next to the old one until the buyer opens it
     * ({@link #promoteReplacementToken}). Asked again within a day, the same token comes back, so repeated
     * requests (a script, or an impatient buyer) invalidate nothing and mint nothing. Empty when the order is not
     * PAID (never revives a refunded or unpaid one). Does not touch {@code email_sent_at}.
     */
    @Transactional
    public Optional<ReplacementToken> issueReplacementToken(Long orderId) {
        PackOrder order = orders.findWithLockById(orderId).orElse(null);
        if (order == null || order.getStatus() != PackOrderStatus.PAID) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        boolean reusable = order.getNextAccessToken() != null && order.getNextAccessTokenIssuedAt() != null
                && order.getNextAccessTokenIssuedAt().plus(REPLACEMENT_REUSE).isAfter(now);
        if (!reusable) {
            order.setNextAccessToken(PackTokens.newToken());
            order.setNextAccessTokenIssuedAt(now);
            orders.save(order);
            log.info("Pack order id={}: replacement access token issued (the current link keeps working)", orderId);
        }
        return Optional.of(new ReplacementToken(order, order.getNextAccessToken()));
    }

    /**
     * Called when someone presents a token that is not any order's current one: if it is this order's pending
     * replacement (and still fresh), it becomes THE token, the old one stops working, and the access window is
     * renewed. Opening the new link is the buyer's own choice, which is why this is the moment the old link dies.
     *
     * @return the order if {@code token} is now (or already was) its current token; empty otherwise
     */
    @Transactional
    public Optional<PackOrder> promoteReplacementToken(Long orderId, String token) {
        PackOrder order = orders.findWithLockById(orderId).orElse(null);
        if (order == null || order.getStatus() != PackOrderStatus.PAID || token == null) {
            return Optional.empty();
        }
        if (token.equals(order.getAccessToken())) {
            return Optional.of(order); // a parallel request promoted it first
        }
        Instant now = clock.instant();
        boolean fresh = order.getNextAccessTokenIssuedAt() != null
                && order.getNextAccessTokenIssuedAt().plus(REPLACEMENT_LIFETIME).isAfter(now);
        if (!token.equals(order.getNextAccessToken()) || !fresh) {
            return Optional.empty();
        }
        order.setAccessToken(order.getNextAccessToken());
        order.setNextAccessToken(null);
        order.setNextAccessTokenIssuedAt(null);
        order.setAccessExpiresAt(now.plus(properties.getAccessDays(), ChronoUnit.DAYS));
        orders.save(order);
        log.info("Pack order id={}: the replacement access token was used; the previous link no longer works", orderId);
        return Optional.of(order);
    }

    private void onApproved(PackOrder order, PaymentSnapshot payment) {
        if (order.getStatus() == PackOrderStatus.PAID) {
            if (!Objects.equals(order.getMpPaymentId(), payment.paymentId())) {
                log.warn("Pack order id={} is already paid by payment {} but payment {} was approved too",
                        order.getId(), order.getMpPaymentId(), payment.paymentId());
                ownerNotifier.notify("Pago duplicado en el pedido " + order.getId(),
                        "El pedido " + order.getId() + " ya estaba pagado con el pago " + order.getMpPaymentId()
                                + ", pero Mercado Pago aprobó también el pago " + payment.paymentId()
                                + ". No se entregó nada nuevo. Probablemente haya que reembolsar uno de los dos.");
            } else {
                log.info("Pack order id={} already PAID - ignoring duplicate notification", order.getId());
            }
            return;
        }
        if (order.getStatus() == PackOrderStatus.REVOKED) {
            // Never resurrected, but never silent either: money may have moved that nobody knows about.
            log.warn("Pack order id={} is REVOKED - ignoring approved payment {}", order.getId(), payment.paymentId());
            ownerNotifier.notify("Pago aprobado en un pedido revocado (pedido " + order.getId() + ")",
                    "Mercado Pago aprobó el pago " + payment.paymentId() + " para el pedido " + order.getId()
                            + ", que estaba revocado por un reembolso o un contracargo. No se entregó nada. "
                            + "Si el comprador volvió a pagar a propósito, hay que armar un pedido nuevo o reembolsar.");
            return;
        }

        if (!amountAndCurrencyMatch(order, payment)) {
            holdForReview(order, payment);
            return;
        }

        Instant now = clock.instant();
        order.setStatus(PackOrderStatus.PAID);
        order.setPaidAt(now);
        order.setAccessExpiresAt(now.plus(properties.getAccessDays(), ChronoUnit.DAYS));
        order.setMpPaymentId(payment.paymentId());
        if (order.isWithGames() && order.getChallengePurchaseId() == null) {
            grantBundledMonth(order);
        }
        orders.save(order);
        log.info("Pack order id={} marked PAID via MP payment={}", order.getId(), payment.paymentId());

        // Published inside this transaction: the email listener runs AFTER it commits (and off this
        // thread), so a slow provider can neither hold this row lock nor undo the payment.
        events.publishEvent(new PackOrderPaidEvent(order.getId()));
    }

    private boolean amountAndCurrencyMatch(PackOrder order, PaymentSnapshot payment) {
        return CURRENCY.equals(payment.currency())
                && payment.amount() != null
                && payment.amount().compareTo(order.getTotalAmount()) == 0;
    }

    private void holdForReview(PackOrder order, PaymentSnapshot payment) {
        boolean firstTime = order.getStatus() != PackOrderStatus.REVIEW;
        order.setStatus(PackOrderStatus.REVIEW);
        order.setMpPaymentId(payment.paymentId());
        orders.save(order);
        log.error("Pack order id={} HELD FOR REVIEW: MP payment {} is {} {} but the order total is {} {}",
                order.getId(), payment.paymentId(), payment.amount(), payment.currency(),
                order.getTotalAmount(), order.getCurrency());
        if (firstTime) {
            ownerNotifier.notify("Pedido " + order.getId() + " en revisión: el monto no coincide",
                    "Mercado Pago aprobó el pago " + payment.paymentId() + " por " + payment.amount() + " "
                            + payment.currency() + ", pero el pedido " + order.getId() + " era por "
                            + order.getTotalAmount() + " " + order.getCurrency()
                            + ". No se entregó nada. Revisalo en Mercado Pago: si corresponde, reembolsá el pago "
                            + "o contactá al comprador.");
        }
    }

    private void onFailed(PackOrder order) {
        if (order.getStatus() == PackOrderStatus.PENDING) {
            order.setStatus(PackOrderStatus.FAILED);
            orders.save(order);
            log.info("Pack order id={} marked FAILED", order.getId());
        } else {
            log.info("Pack order id={} is {} - a rejected/cancelled payment does not change it",
                    order.getId(), order.getStatus());
        }
    }

    private void onReversed(PackOrder order, PaymentSnapshot payment) {
        if (order.getStatus() == PackOrderStatus.REVOKED) {
            return;
        }
        // A refund of some OTHER payment (e.g. a failed duplicate attempt) must not close an order that
        // another payment legitimately paid.
        if (order.getMpPaymentId() != null && !order.getMpPaymentId().equals(payment.paymentId())) {
            log.info("Pack order id={}: payment {} was {}, but the order was paid by {} - ignoring",
                    order.getId(), payment.paymentId(), payment.status(), order.getMpPaymentId());
            return;
        }
        order.setStatus(PackOrderStatus.REVOKED);
        orders.save(order);
        log.warn("Pack order id={} REVOKED: MP payment {} was {}", order.getId(), payment.paymentId(), payment.status());
        if (order.getChallengePurchaseId() != null) {
            challengePurchases.deactivatePurchase(order.getChallengePurchaseId());
        }
    }

    /**
     * Grants the Desafío month bundled with the games add-on. Never lets a problem here undo the payment:
     * the pack was paid for and is delivered either way, and a month that cannot be granted is reported
     * to the owner to resolve by hand.
     */
    private void grantBundledMonth(PackOrder order) {
        String product = order.getEdition().productName(); // which product, for the owner's alert
        String refund = ars(order.getGamesAmount());       // what this order really paid for the Desafío
        if (!StringUtils.hasText(order.getPhone())) {
            log.error("Pack order id={} bought the games add-on without a phone; no Desafío month granted", order.getId());
            ownerNotifier.notify("Pedido " + order.getId() + " (" + product + "): falta el mes del Desafío",
                    "El pedido " + order.getId() + " (" + product + ") incluye el mes del Desafío pero no tiene teléfono, "
                            + "así que no se pudo dar. Los archivos sí se entregaron y el comprador NO recibe ningún aviso "
                            + "del Desafío. Para resolverlo, reembolsá " + refund + " (lo que pagó por el Desafío) o "
                            + "contactá al comprador (" + order.getEmail() + ").");
            return;
        }
        // The Desafío service serialises months per phone with a JVM lock that is released BEFORE this transaction
        // commits, so two packs for the same phone paid at the same moment could both see "month 1 is free" and
        // both be given it. Holding a database lock per phone until this transaction ends closes that window.
        orders.lockUntilTransactionEnds("pack-games-phone:" + order.getPhone());
        Optional<ChallengePurchase> granted = challengePurchases.grantBundledMonth(
                order.getBuyerName(), order.getPhone(), order.getEmail());
        if (granted.isPresent()) {
            order.setChallengePurchaseId(granted.get().getId());
        } else {
            log.error("Pack order id={}: the phone already has every Desafío month on sale; none granted", order.getId());
            ownerNotifier.notify("Pedido " + order.getId() + " (" + product + "): no quedaba un mes del Desafío para dar",
                    "El pedido " + order.getId() + " (" + product + ") incluye el mes del Desafío pero el teléfono ya "
                            + "tenía todos los meses en venta. Los archivos sí se entregaron y el comprador NO recibe "
                            + "ningún aviso del Desafío. Para resolverlo, reembolsá " + refund + " (lo que pagó por el "
                            + "Desafío) o resolvelo con el comprador (" + order.getEmail() + ").");
        }
    }

    /** "ARS 15.000" or "ARS 12.500,50": an amount the way the owner reads it (Argentine separators). */
    private static String ars(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        boolean cents = value.stripTrailingZeros().scale() > 0;
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-AR"));
        format.setMinimumFractionDigits(cents ? 2 : 0);
        format.setMaximumFractionDigits(cents ? 2 : 0);
        return "ARS " + format.format(value);
    }
}
