package com.tiam.challenge.service;

import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.tiam.challenge.config.ChallengeReconciliationProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.subscription.service.MercadoPagoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the Task 2 reconciliation safety net: a scheduled job that recovers
 * {@code challenge_purchases} rows stuck in PENDING because their Mercado Pago
 * webhook notification was never delivered (or was permanently lost, even after
 * MP's own retries).
 */
@ExtendWith(MockitoExtension.class)
class ChallengePurchaseReconciliationServiceTest {

    @Mock ChallengePurchaseRepository challengePurchaseRepository;
    @Mock MercadoPagoService mercadoPagoService;
    @Mock ChallengePurchaseService challengePurchaseService;

    ChallengeReconciliationProperties properties;
    ChallengePurchaseReconciliationService service;

    @BeforeEach
    void setUp() {
        properties = new ChallengeReconciliationProperties();
        properties.setEnabled(true);
        properties.setGracePeriodMinutes(10);
        properties.setLookbackDays(30);

        service = new ChallengePurchaseReconciliationService(
                challengePurchaseRepository, mercadoPagoService, challengePurchaseService, properties);
    }

    @Test
    void reconcilePendingPurchases_approvedInMp_marksPaid() throws Exception {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        ChallengePurchase purchase = stalePendingPurchase(42L);
        givenCandidates(purchase);
        Payment payment = paymentWithIdAndStatus(999L, "approved");
        when(mercadoPagoService.findLatestPaymentByExternalReference("42")).thenReturn(Optional.of(payment));

        service.reconcilePendingPurchases();

        verify(challengePurchaseService).markPaid(42L, "999");
    }

    @Test
    void reconcilePendingPurchases_rejectedInMp_marksFailed() throws Exception {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        ChallengePurchase purchase = stalePendingPurchase(42L);
        givenCandidates(purchase);
        Payment payment = paymentWithStatus("rejected");
        when(mercadoPagoService.findLatestPaymentByExternalReference("42")).thenReturn(Optional.of(payment));

        service.reconcilePendingPurchases();

        verify(challengePurchaseService).markFailed(42L);
    }

    @Test
    void reconcilePendingPurchases_cancelledInMp_marksFailed() throws Exception {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        ChallengePurchase purchase = stalePendingPurchase(42L);
        givenCandidates(purchase);
        Payment payment = paymentWithStatus("cancelled");
        when(mercadoPagoService.findLatestPaymentByExternalReference("42")).thenReturn(Optional.of(payment));

        service.reconcilePendingPurchases();

        verify(challengePurchaseService).markFailed(42L);
    }

    @Test
    void reconcilePendingPurchases_notFoundInMp_leavesUntouched() throws Exception {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        ChallengePurchase purchase = stalePendingPurchase(42L);
        givenCandidates(purchase);
        when(mercadoPagoService.findLatestPaymentByExternalReference("42")).thenReturn(Optional.empty());

        service.reconcilePendingPurchases();

        verify(challengePurchaseService, never()).markPaid(any(), any());
        verify(challengePurchaseService, never()).markFailed(any());
    }

    @Test
    void reconcilePendingPurchases_onePurchaseThrows_othersStillProcessed() throws Exception {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        ChallengePurchase failing = stalePendingPurchase(1L);
        ChallengePurchase healthy = stalePendingPurchase(2L);
        givenCandidates(failing, healthy);
        when(mercadoPagoService.findLatestPaymentByExternalReference("1"))
                .thenThrow(new MPException("MP API blip"));
        Payment approvedPayment = paymentWithIdAndStatus(555L, "approved");
        when(mercadoPagoService.findLatestPaymentByExternalReference("2"))
                .thenReturn(Optional.of(approvedPayment));

        service.reconcilePendingPurchases();

        verify(challengePurchaseService).markPaid(2L, "555");
        verify(challengePurchaseService, never()).markPaid(eq(1L), any());
    }

    @Test
    void reconcilePendingPurchases_disabled_noOp() {
        properties.setEnabled(false);

        service.reconcilePendingPurchases();

        verifyNoInteractions(challengePurchaseRepository, mercadoPagoService, challengePurchaseService);
    }

    @Test
    void reconcilePendingPurchases_mpNotConfigured_noOp() {
        when(mercadoPagoService.isConfigured()).thenReturn(false);

        service.reconcilePendingPurchases();

        verifyNoInteractions(challengePurchaseRepository, challengePurchaseService);
    }

    @Test
    void reconcilePendingPurchases_purchaseInsideGracePeriod_skipped() throws Exception {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        // Created just 2 minutes ago — well inside the 10-minute grace period. A real
        // repository query would already exclude this row; this test guards the
        // service's own defensive re-check in case that query's bounds ever drift.
        ChallengePurchase tooRecent = stalePendingPurchase(42L);
        tooRecent.setCreatedAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        givenCandidates(tooRecent);

        service.reconcilePendingPurchases();

        verify(mercadoPagoService, never()).findLatestPaymentByExternalReference(any());
        verifyNoInteractions(challengePurchaseService);
    }

    @Test
    void reconcilePendingPurchases_defaultLookback_includesPurchaseCreated20DaysAgo() throws Exception {
        // Regression guard (SHOULD-FIX from adversarial review): Checkout Pro offers
        // cash-coupon methods (Rapipago / Pago Fácil) alongside cards, and those are
        // paid in person and can confirm with MP several days after the purchase was
        // created. With the OLD 7-day lookback default, a purchase created 20 days ago
        // would already have been excluded from the candidate window forever, even if
        // it later paid via cash coupon — the customer paid and would never get access.
        //
        // Deliberately uses a FRESH, untouched ChallengeReconciliationProperties (no
        // explicit setLookbackDays call) so this test exercises the class's actual
        // default field value, not the 30-day value the shared @BeforeEach fixture
        // configures explicitly — otherwise this would pass even if the default were
        // reverted to 7 and the whole point of the regression guard would be lost.
        ChallengeReconciliationProperties defaultProperties = new ChallengeReconciliationProperties();
        ChallengePurchaseReconciliationService defaultService = new ChallengePurchaseReconciliationService(
                challengePurchaseRepository, mercadoPagoService, challengePurchaseService, defaultProperties);
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.findByStatusAndActivoTrueAndCreatedAtBetween(
                eq(ChallengePurchaseStatus.PENDING), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        defaultService.reconcilePendingPurchases();

        ArgumentCaptor<Instant> lookbackFromCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(challengePurchaseRepository).findByStatusAndActivoTrueAndCreatedAtBetween(
                eq(ChallengePurchaseStatus.PENDING), lookbackFromCaptor.capture(), any(Instant.class));

        Instant purchaseCreated20DaysAgo = Instant.now().minus(20, ChronoUnit.DAYS);
        assertThat(lookbackFromCaptor.getValue()).isBefore(purchaseCreated20DaysAgo);
    }

    @Test
    void reconcilePendingPurchases_noCandidates_noMpInteraction() throws Exception {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.findByStatusAndActivoTrueAndCreatedAtBetween(
                eq(ChallengePurchaseStatus.PENDING), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        service.reconcilePendingPurchases();

        verify(mercadoPagoService, never()).findLatestPaymentByExternalReference(any());
        verifyNoInteractions(challengePurchaseService);
    }

    // --- fixtures -------------------------------------------------------------

    private void givenCandidates(ChallengePurchase... purchases) {
        when(challengePurchaseRepository.findByStatusAndActivoTrueAndCreatedAtBetween(
                eq(ChallengePurchaseStatus.PENDING), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(purchases));
    }

    /** PENDING, created 20 minutes ago — past the default 10-minute grace period. */
    private ChallengePurchase stalePendingPurchase(long id) {
        ChallengePurchase purchase = new ChallengePurchase();
        purchase.setId(id);
        purchase.setStatus(ChallengePurchaseStatus.PENDING);
        purchase.setCreatedAt(Instant.now().minus(20, ChronoUnit.MINUTES));
        purchase.setBuyerName("Test Buyer");
        purchase.setPhone("+5491100000000");
        purchase.setAccessToken("token-" + id);
        return purchase;
    }

    /** For approved payments — the production code reads getId() to pass as mpPaymentId. */
    private Payment paymentWithIdAndStatus(Long id, String status) {
        Payment payment = mock(Payment.class);
        when(payment.getId()).thenReturn(id);
        when(payment.getStatus()).thenReturn(status);
        return payment;
    }

    /** For rejected/cancelled/other payments — the production code never reads getId(). */
    private Payment paymentWithStatus(String status) {
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(status);
        return payment;
    }
}
