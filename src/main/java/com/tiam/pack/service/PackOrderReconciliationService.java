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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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

    /**
     * When each order was last asked about (in memory; empty after a restart, which only means one extra round
     * of questions). Used to ask less and less often about orders that stay unpaid.
     */
    private final Map<Long, Instant> lastChecked = new ConcurrentHashMap<>();

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

        // PENDING: the notification may simply never have arrived. FAILED: the buyer may have retried after a
        // rejection and paid on the same checkout. Keyed by id so an order is never asked about twice per run.
        Map<Long, PackOrder> byId = new LinkedHashMap<>();
        orders.findStalePending(PackOrderStatus.PENDING, lookbackThreshold, graceThreshold)
                .forEach(order -> byId.putIfAbsent(order.getId(), order));
        orders.findStalePending(PackOrderStatus.FAILED,
                now.minus(settings.getFailedLookbackDays(), ChronoUnit.DAYS), graceThreshold)
                .forEach(order -> byId.putIfAbsent(order.getId(), order));
        lastChecked.keySet().retainAll(byId.keySet()); // forget orders that left the window

        List<PackOrder> candidates = byId.values().stream()
                .filter(order -> dueForCheck(order, now))
                .sorted(Comparator.comparing(PackOrderReconciliationService::createdAtOrEpoch).reversed()) // newest first
                .limit(settings.getMaxPerRun())
                .collect(Collectors.toCollection(ArrayList::new));
        if (candidates.isEmpty()) {
            return;
        }

        log.info("Pack order reconciliation: inspecting {} stale order(s) ({} in the window)", candidates.size(), byId.size());
        Instant started = clock.instant();
        for (PackOrder order : candidates) {
            if (Duration.between(started, clock.instant()).toSeconds() >= settings.getMaxRunSeconds()) {
                // Each Mercado Pago call can wait for its own timeout; do not let one run hold a scheduler thread for
                // minutes. The orders not reached are not marked as checked, so the next run starts with them.
                log.warn("Pack order reconciliation: time budget of {}s spent, stopping early", settings.getMaxRunSeconds());
                break;
            }
            lastChecked.put(order.getId(), now);
            try {
                reconcileOne(order, graceThreshold);
            } catch (Exception e) {
                // One bad order must not abort the run.
                log.error("Pack order reconciliation failed for order id={}: {}", order.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Young orders are asked about on every run; an order that has been unpaid for more than an hour only every
     * hour, and for more than a day only every six hours. Without this, every abandoned cart would cost one
     * Mercado Pago search every 15 minutes for two weeks.
     */
    private boolean dueForCheck(PackOrder order, Instant now) {
        Instant last = lastChecked.get(order.getId());
        if (last == null) {
            return true;
        }
        Duration age = Duration.between(createdAtOrEpoch(order), now);
        Duration interval = age.compareTo(Duration.ofDays(1)) >= 0 ? Duration.ofHours(6)
                : age.compareTo(Duration.ofHours(1)) >= 0 ? Duration.ofHours(1)
                : Duration.ZERO;
        return !last.plus(interval).isAfter(now);
    }

    private static Instant createdAtOrEpoch(PackOrder order) {
        return order.getCreatedAt() != null ? order.getCreatedAt() : Instant.EPOCH;
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
