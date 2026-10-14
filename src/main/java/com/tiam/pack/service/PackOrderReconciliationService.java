package com.tiam.pack.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.subscription.service.MercadoPagoService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Safety net for lost Mercado Pago notifications, the pack twin of the Desafío's reconciliation job: a
 * pack order can stay PENDING forever if its webhook never arrives. This job asks Mercado Pago what really
 * happened to every stale PENDING order (searching payments by {@code external_reference = pack:<id>}) and
 * applies the answer through {@link PackOrderService#onPayment}, so the amount and currency checks are the
 * same as on the webhook path.
 *
 * <p>The window is [now - lookback, now - grace]: the preference expires after 7 days, so looking back 14 is
 * generous, and cash-coupon payments that confirm days later are covered. Orders younger than the grace period
 * are left to the webhook.
 *
 * <p>Single-instance assumption, as for the Desafío job: no distributed lock. With several instances the row
 * lock inside {@code onPayment} still prevents a double delivery, but both could do the work.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackOrderReconciliationService {

    private final PackOrderRepository orders;
    private final MercadoPagoService mercadoPago;
    private final PackOrderService packOrderService;
    private final PackProperties properties;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${tiam.pack.reconciliation.interval-ms:900000}")
    public void reconcilePendingOrders() {
        PackProperties.Reconciliation settings = properties.getReconciliation();
        if (!settings.isEnabled()) {
            log.debug("Pack order reconciliation is disabled (tiam.pack.reconciliation.enabled=false) - skipping");
            return;
        }
        if (!mercadoPago.isConfigured()) {
            log.debug("Pack order reconciliation skipped - Mercado Pago is not configured");
            return;
        }

        Instant now = clock.instant();
        Instant graceThreshold = now.minus(settings.getGracePeriodMinutes(), ChronoUnit.MINUTES);
        Instant lookbackThreshold = now.minus(settings.getLookbackDays(), ChronoUnit.DAYS);

        List<PackOrder> candidates = orders.findStalePending(PackOrderStatus.PENDING, lookbackThreshold, graceThreshold);
        if (candidates.isEmpty()) {
            return;
        }

        log.info("Pack order reconciliation: inspecting {} stale PENDING order(s)", candidates.size());
        for (PackOrder order : candidates) {
            try {
                reconcileOne(order, graceThreshold);
            } catch (Exception e) {
                // One bad order must not abort the run.
                log.error("Pack order reconciliation failed for order id={}: {}", order.getId(), e.getMessage(), e);
            }
        }
    }

    private void reconcileOne(PackOrder order, Instant graceThreshold) throws MPException, MPApiException {
        if (order.getCreatedAt() != null && order.getCreatedAt().isAfter(graceThreshold)) {
            return; // defensive: still inside the grace period
        }
        Optional<Payment> found = mercadoPago.findLatestPaymentByExternalReference(order.externalReference());
        if (found.isEmpty()) {
            log.debug("Pack order reconciliation: no MP payment found for order id={}", order.getId());
            return;
        }
        Payment payment = found.get();
        log.info("Pack order reconciliation: order id={} has MP payment {} with status {}",
                order.getId(), payment.getId(), payment.getStatus());
        packOrderService.onPayment(order.getId(), PaymentSnapshot.from(String.valueOf(payment.getId()), payment));
    }
}
