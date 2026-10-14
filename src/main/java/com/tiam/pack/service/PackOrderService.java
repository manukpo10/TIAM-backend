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
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /** The result of {@link #rotateAccess}: the order with its NEW token, and what to put back if delivery fails. */
    public record AccessRotation(PackOrder order, String previousToken, Instant previousExpiry) {
    }

    /**
     * Gives a paid order a brand-new access token and a fresh access window, invalidating the old link (which
     * may have leaked). Used when the buyer asks for their access again. Empty when the order is not PAID
     * (never revives a refunded or unpaid one). Does not touch {@code email_sent_at}.
     */
    @Transactional
    public Optional<AccessRotation> rotateAccess(Long orderId) {
        PackOrder order = orders.findWithLockById(orderId).orElse(null);
        if (order == null || order.getStatus() != PackOrderStatus.PAID) {
            return Optional.empty();
        }
        AccessRotation rotation = new AccessRotation(order, order.getAccessToken(), order.getAccessExpiresAt());
        order.setAccessToken(PackTokens.newToken());
        order.setAccessExpiresAt(clock.instant().plus(properties.getAccessDays(), ChronoUnit.DAYS));
        orders.save(order);
        log.info("Pack order id={}: access token rotated and access window renewed", orderId);
        return Optional.of(rotation);
    }

    /**
     * Undoes a rotation whose email could not be delivered, so the buyer is not left with a dead old link and
     * a new link they never received. A no-op if the token has changed again since.
     */
    @Transactional
    public void restoreAccess(Long orderId, String rotatedToken, String previousToken, Instant previousExpiry) {
        orders.findWithLockById(orderId).ifPresent(order -> {
            if (rotatedToken != null && rotatedToken.equals(order.getAccessToken())) {
                order.setAccessToken(previousToken);
                order.setAccessExpiresAt(previousExpiry);
                orders.save(order);
                log.warn("Pack order id={}: access rotation undone because the new link could not be emailed", orderId);
            }
        });
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
        if (!StringUtils.hasText(order.getPhone())) {
            log.error("Pack order id={} bought the games add-on without a phone; no Desafío month granted", order.getId());
            ownerNotifier.notify("Pedido " + order.getId() + ": falta el mes de juegos",
                    "El pedido " + order.getId() + " incluye el mes de juegos pero no tiene teléfono, así que no se "
                            + "pudo activar. El pack sí se entregó. Contactá al comprador (" + order.getEmail() + ").");
            return;
        }
        Optional<ChallengePurchase> granted = challengePurchases.grantBundledMonth(
                order.getBuyerName(), order.getPhone(), order.getEmail());
        if (granted.isPresent()) {
            order.setChallengePurchaseId(granted.get().getId());
        } else {
            log.error("Pack order id={}: the phone already has every Desafío month on sale; none granted", order.getId());
            ownerNotifier.notify("Pedido " + order.getId() + ": no quedaba un mes de juegos para activar",
                    "El pedido " + order.getId() + " incluye el mes de juegos pero el teléfono ya tenía todos los "
                            + "meses activos. El pack sí se entregó. Habrá que reembolsar el mes de juegos "
                            + "o resolverlo con el comprador (" + order.getEmail() + ").");
        }
    }
}
