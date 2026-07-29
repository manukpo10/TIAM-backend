package com.tiam.challenge.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.tiam.challenge.config.ChallengeReconciliationProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.subscription.service.MercadoPagoService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Safety net for the Mercado Pago webhook: a {@code challenge_purchases} row can stay
 * PENDING forever if its webhook notification is lost even after MP's own retries (see
 * {@link com.tiam.subscription.web.MercadoPagoWebhookController}). This job periodically
 * re-asks MP what actually happened to any stale PENDING purchase and reconciles it.
 *
 * <p>PENDING purchases have no {@code mp_payment_id} (it's only set by
 * {@link ChallengePurchaseService#markPaid}), so they can't be looked up by payment id —
 * instead this searches MP's Payment API by {@code external_reference}, which is set to
 * the local purchase id at checkout creation time (see
 * {@link MercadoPagoService#findLatestPaymentByExternalReference}).
 *
 * <p><b>Single-instance assumption:</b> this job does not use a distributed lock. That's
 * safe for the current single-instance Render deployment, where only one JVM ever runs
 * this method. If the service is ever scaled to multiple instances, this needs a lock
 * (e.g. a DB advisory lock or a "reconciliation in progress" row) so two instances don't
 * reconcile — and double-process — the same purchase concurrently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengePurchaseReconciliationService {

    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";
    private static final String STATUS_CANCELLED = "cancelled";

    private final ChallengePurchaseRepository challengePurchaseRepository;
    private final MercadoPagoService mercadoPagoService;
    private final ChallengePurchaseService challengePurchaseService;
    private final ChallengeReconciliationProperties reconciliationProperties;

    @Scheduled(fixedDelayString = "${tiam.challenge.reconciliation.interval-ms:900000}")
    public void reconcilePendingPurchases() {
        if (!reconciliationProperties.isEnabled()) {
            log.info("Challenge purchase reconciliation is disabled "
                    + "(tiam.challenge.reconciliation.enabled=false) — skipping");
            return;
        }

        if (!mercadoPagoService.isConfigured()) {
            log.info("Challenge purchase reconciliation skipped — Mercado Pago is not configured");
            return;
        }

        Instant now = Instant.now();
        Instant graceThreshold = now.minus(reconciliationProperties.getGracePeriodMinutes(), ChronoUnit.MINUTES);
        Instant lookbackThreshold = now.minus(reconciliationProperties.getLookbackDays(), ChronoUnit.DAYS);

        List<ChallengePurchase> candidates = challengePurchaseRepository
                .findByStatusAndActivoTrueAndCreatedAtBetween(
                        ChallengePurchaseStatus.PENDING, lookbackThreshold, graceThreshold);

        if (candidates.isEmpty()) {
            return;
        }

        log.info("Challenge purchase reconciliation: inspecting {} stale PENDING purchase(s)", candidates.size());

        for (ChallengePurchase purchase : candidates) {
            try {
                reconcileOne(purchase, graceThreshold);
            } catch (Exception e) {
                // One bad purchase must not abort the whole run — log and move on.
                log.error("Challenge purchase reconciliation failed for purchase id={}: {}",
                        purchase.getId(), e.getMessage(), e);
            }
        }
    }

    private void reconcileOne(ChallengePurchase purchase, Instant graceThreshold)
            throws MPException, MPApiException {
        // Defensive re-check even though the repository query already applies the same
        // bound — protects reconciliation correctness if that query's semantics ever
        // drift (e.g. an off-by-one on inclusive/exclusive bounds).
        if (purchase.getCreatedAt() != null && purchase.getCreatedAt().isAfter(graceThreshold)) {
            log.debug("Challenge purchase reconciliation: purchase id={} is still within the grace period "
                    + "— skipping", purchase.getId());
            return;
        }

        String externalReference = String.valueOf(purchase.getId());
        Optional<Payment> found = mercadoPagoService.findLatestPaymentByExternalReference(externalReference);

        if (found.isEmpty()) {
            log.debug("Challenge purchase reconciliation: no MP payment found for purchase id={}",
                    purchase.getId());
            return;
        }

        Payment payment = found.get();
        String status = payment.getStatus();

        if (STATUS_APPROVED.equals(status)) {
            String paymentId = String.valueOf(payment.getId());
            log.info("Challenge purchase reconciliation: purchase id={} is approved in MP (paymentId={}) "
                    + "— marking PAID", purchase.getId(), paymentId);
            challengePurchaseService.markPaid(purchase.getId(), paymentId);
        } else if (STATUS_REJECTED.equals(status) || STATUS_CANCELLED.equals(status)) {
            log.info("Challenge purchase reconciliation: purchase id={} is {} in MP — marking FAILED",
                    purchase.getId(), status);
            challengePurchaseService.markFailed(purchase.getId());
        } else {
            log.debug("Challenge purchase reconciliation: purchase id={} is still {} in MP — leaving alone",
                    purchase.getId(), status);
        }
    }
}
