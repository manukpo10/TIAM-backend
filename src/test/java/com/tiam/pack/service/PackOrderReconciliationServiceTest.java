package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mercadopago.resources.payment.Payment;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.subscription.service.MercadoPagoService;
import com.tiam.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackOrderReconciliationServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");

    @Mock PackOrderRepository orders;
    @Mock MercadoPagoService mercadoPago;
    @Mock PackOrderService packOrderService;

    PackProperties properties;
    MutableClock clock;
    PackOrderReconciliationService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        clock = new MutableClock(NOW);
        service = new PackOrderReconciliationService(orders, mercadoPago, packOrderService, properties, clock);
    }

    @Test
    void disabled_doesNothing() {
        properties.getReconciliation().setEnabled(false);

        service.reconcilePendingOrders();

        verifyNoInteractions(orders, mercadoPago, packOrderService);
    }

    @Test
    void mercadoPagoNotConfigured_doesNothing() {
        when(mercadoPago.isConfigured()).thenReturn(false);

        service.reconcilePendingOrders();

        verifyNoInteractions(orders, packOrderService);
    }

    @Test
    void looksOnlyAtPendingOrdersBetweenTheLookbackAndTheGracePeriod() {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.findStalePending(any(), any(), any())).thenReturn(List.of());

        service.reconcilePendingOrders();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(orders).findStalePending(eq(PackOrderStatus.PENDING), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(NOW.minus(Duration.ofDays(14)));
        assertThat(to.getValue()).isEqualTo(NOW.minus(Duration.ofMinutes(10)));
    }

    @Test
    void anApprovedPaymentFoundInMercadoPago_isAppliedThroughTheSameServiceAsTheWebhook() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.findStalePending(any(), any(), any())).thenReturn(List.of(stale(42L)));
        Payment payment777 = payment(777L, "approved");
        when(mercadoPago.findLatestPaymentByExternalReference("pack:42")).thenReturn(Optional.of(payment777));

        service.reconcilePendingOrders();

        ArgumentCaptor<PaymentSnapshot> snapshot = ArgumentCaptor.forClass(PaymentSnapshot.class);
        verify(packOrderService).onPayment(eq(42L), snapshot.capture());
        assertThat(snapshot.getValue().paymentId()).isEqualTo("777");
        assertThat(snapshot.getValue().status()).isEqualTo("approved");
        assertThat(snapshot.getValue().amount()).isEqualByComparingTo("14990.00");
        assertThat(snapshot.getValue().currency()).isEqualTo("ARS");
    }

    @Test
    void aRejectedPayment_isAppliedToo() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.findStalePending(any(), any(), any())).thenReturn(List.of(stale(42L)));
        Payment payment777 = payment(777L, "rejected");
        when(mercadoPago.findLatestPaymentByExternalReference("pack:42")).thenReturn(Optional.of(payment777));

        service.reconcilePendingOrders();

        ArgumentCaptor<PaymentSnapshot> snapshot = ArgumentCaptor.forClass(PaymentSnapshot.class);
        verify(packOrderService).onPayment(eq(42L), snapshot.capture());
        assertThat(snapshot.getValue().status()).isEqualTo("rejected");
    }

    @Test
    void noPaymentInMercadoPago_leavesTheOrderAlone() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.findStalePending(any(), any(), any())).thenReturn(List.of(stale(42L)));
        when(mercadoPago.findLatestPaymentByExternalReference("pack:42")).thenReturn(Optional.empty());

        service.reconcilePendingOrders();

        verify(packOrderService, never()).onPayment(any(), any());
    }

    @Test
    void aStillPendingPayment_isPassedAlongAndChangesNothing() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.findStalePending(any(), any(), any())).thenReturn(List.of(stale(42L)));
        Payment payment777 = payment(777L, "pending");
        when(mercadoPago.findLatestPaymentByExternalReference("pack:42")).thenReturn(Optional.of(payment777));

        service.reconcilePendingOrders();

        verify(packOrderService).onPayment(eq(42L), any(PaymentSnapshot.class)); // the service itself ignores "pending"
    }

    @Test
    void oneFailingOrder_doesNotStopTheOthers() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.findStalePending(any(), any(), any())).thenReturn(List.of(stale(1L), stale(2L), stale(3L)));
        when(mercadoPago.findLatestPaymentByExternalReference("pack:1")).thenThrow(new RuntimeException("MP blip"));
        Payment second = payment(20L, "approved");
        Payment third = payment(30L, "approved");
        when(mercadoPago.findLatestPaymentByExternalReference("pack:2")).thenReturn(Optional.of(second));
        when(mercadoPago.findLatestPaymentByExternalReference("pack:3")).thenReturn(Optional.of(third));
        doThrow(new IllegalStateException("db")).when(packOrderService).onPayment(eq(2L), any());

        service.reconcilePendingOrders();

        verify(packOrderService).onPayment(eq(2L), any());
        verify(packOrderService).onPayment(eq(3L), any());
    }

    @Test
    void anOrderYoungerThanTheGracePeriod_isSkippedEvenIfTheQueryReturnedIt() throws Exception {
        PackOrder young = stale(42L);
        young.setCreatedAt(NOW.minus(Duration.ofMinutes(2)));
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.findStalePending(any(), any(), any())).thenReturn(List.of(young));

        service.reconcilePendingOrders();

        verify(mercadoPago, never()).findLatestPaymentByExternalReference(any());
    }

    // --- rejected-then-paid and load control ------------------------------------------------------------------

    @Test
    void alsoLooksAtRecentlyRejectedOrders_becauseABuyerCanRetryAndPayOnTheSameCheckout() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        PackOrder failed = stale(42L);
        failed.setStatus(PackOrderStatus.FAILED);
        when(orders.findStalePending(eq(PackOrderStatus.PENDING), any(), any())).thenReturn(List.of());
        when(orders.findStalePending(eq(PackOrderStatus.FAILED), any(), any())).thenReturn(List.of(failed));
        Payment approvedLater = payment(777L, "approved");
        when(mercadoPago.findLatestPaymentByExternalReference("pack:42")).thenReturn(Optional.of(approvedLater));

        service.reconcilePendingOrders();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(orders).findStalePending(eq(PackOrderStatus.FAILED), from.capture(), any());
        assertThat(from.getValue()).isEqualTo(NOW.minus(Duration.ofDays(3)));
        verify(packOrderService).onPayment(eq(42L), any(PaymentSnapshot.class));
    }

    @Test
    void anOrderListedByBothQueries_isCheckedOnlyOnce() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.findStalePending(any(), any(), any())).thenReturn(List.of(stale(42L)));
        Payment payment = payment(777L, "approved");
        when(mercadoPago.findLatestPaymentByExternalReference("pack:42")).thenReturn(Optional.of(payment));

        service.reconcilePendingOrders();

        verify(mercadoPago, times(1)).findLatestPaymentByExternalReference("pack:42");
    }

    @Test
    void anOrderAskedAboutRecently_isNotAskedAgainUntilItsIntervalPasses() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        PackOrder old = stale(42L);
        old.setCreatedAt(NOW.minus(Duration.ofDays(3))); // older than a day: checked every 6 hours
        when(orders.findStalePending(eq(PackOrderStatus.PENDING), any(), any())).thenReturn(List.of(old));
        when(mercadoPago.findLatestPaymentByExternalReference("pack:42")).thenReturn(Optional.empty());

        service.reconcilePendingOrders();
        clock.advance(Duration.ofMinutes(15));
        service.reconcilePendingOrders();
        clock.advance(Duration.ofHours(1));
        service.reconcilePendingOrders();
        verify(mercadoPago, times(1)).findLatestPaymentByExternalReference("pack:42");

        clock.advance(Duration.ofHours(6));
        service.reconcilePendingOrders();
        verify(mercadoPago, times(2)).findLatestPaymentByExternalReference("pack:42");
    }

    @Test
    void aFreshOrder_isCheckedOnEveryRun() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        PackOrder fresh = stale(42L);
        fresh.setCreatedAt(NOW.minus(Duration.ofMinutes(20)));
        when(orders.findStalePending(eq(PackOrderStatus.PENDING), any(), any())).thenReturn(List.of(fresh));
        when(mercadoPago.findLatestPaymentByExternalReference("pack:42")).thenReturn(Optional.empty());

        service.reconcilePendingOrders();
        clock.advance(Duration.ofMinutes(15));
        service.reconcilePendingOrders();

        verify(mercadoPago, times(2)).findLatestPaymentByExternalReference("pack:42");
    }

    @Test
    void aSingleRunNeverAsksMercadoPagoAboutMoreThanTheConfiguredCap_newestFirst() throws Exception {
        properties.getReconciliation().setMaxPerRun(3);
        when(mercadoPago.isConfigured()).thenReturn(true);
        List<PackOrder> candidates = new java.util.ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            PackOrder order = stale(id);
            order.setCreatedAt(NOW.minus(Duration.ofHours(1)).minusSeconds(100 - id)); // higher id = newer
            candidates.add(order);
        }
        when(orders.findStalePending(eq(PackOrderStatus.PENDING), any(), any())).thenReturn(candidates);
        when(mercadoPago.findLatestPaymentByExternalReference(any())).thenReturn(Optional.empty());

        service.reconcilePendingOrders();

        verify(mercadoPago, times(3)).findLatestPaymentByExternalReference(any());
        verify(mercadoPago).findLatestPaymentByExternalReference("pack:10");
        verify(mercadoPago).findLatestPaymentByExternalReference("pack:9");
        verify(mercadoPago).findLatestPaymentByExternalReference("pack:8");
    }

    @Test
    void aRunStopsWhenItsTimeBudgetIsSpent_soASlowMercadoPagoCannotKeepTheSchedulerBusyForMinutes() throws Exception {
        properties.getReconciliation().setMaxRunSeconds(100);
        when(mercadoPago.isConfigured()).thenReturn(true);
        List<PackOrder> candidates = new java.util.ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            PackOrder order = stale(id);
            order.setCreatedAt(NOW.minus(Duration.ofHours(1)).minusSeconds(100 - id));
            candidates.add(order);
        }
        when(orders.findStalePending(eq(PackOrderStatus.PENDING), any(), any())).thenReturn(candidates);
        when(mercadoPago.findLatestPaymentByExternalReference(any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(50)); // every call to a struggling Mercado Pago takes 50 s
            return Optional.empty();
        });

        service.reconcilePendingOrders();

        verify(mercadoPago, times(2)).findLatestPaymentByExternalReference(any()); // 50 s + 50 s = the budget
        // the ones not reached are not marked as checked: the next run picks them up
        service.reconcilePendingOrders();
        verify(mercadoPago, times(4)).findLatestPaymentByExternalReference(any());
    }

    // --- refunds that nobody told us about (M-09) ---------------------------------------------------------------------

    @Test
    void aPaidOrderWhosePaymentWasRefundedInMercadoPago_isRevoked_evenIfTheNotificationWasLost() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        givenNoUnpaidOrders();
        PackOrder paid = paid(42L, "777");
        when(orders.findStalePending(eq(PackOrderStatus.PAID), any(), any())).thenReturn(List.of(paid));
        Payment refunded = payment(777L, "refunded");
        when(mercadoPago.getPayment("777")).thenReturn(refunded);

        service.reconcilePendingOrders();

        ArgumentCaptor<PaymentSnapshot> snapshot = ArgumentCaptor.forClass(PaymentSnapshot.class);
        verify(packOrderService).onPayment(eq(42L), snapshot.capture());
        assertThat(snapshot.getValue().status()).isEqualTo("refunded");
        assertThat(snapshot.getValue().paymentId()).isEqualTo("777");
    }

    @Test
    void aChargeback_isRevokedToo() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        givenNoUnpaidOrders();
        when(orders.findStalePending(eq(PackOrderStatus.PAID), any(), any())).thenReturn(List.of(paid(42L, "777")));
        Payment chargedBack = payment(777L, "charged_back");
        when(mercadoPago.getPayment("777")).thenReturn(chargedBack);

        service.reconcilePendingOrders();

        verify(packOrderService).onPayment(eq(42L), any());
    }

    @Test
    void aPaidOrderWhosePaymentIsStillApproved_isLeftAlone_andIsNotReprocessed() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        givenNoUnpaidOrders();
        when(orders.findStalePending(eq(PackOrderStatus.PAID), any(), any())).thenReturn(List.of(paid(42L, "777")));
        Payment approved = mock(Payment.class);
        when(approved.getStatus()).thenReturn("approved");
        when(mercadoPago.getPayment("777")).thenReturn(approved);

        service.reconcilePendingOrders();

        verify(packOrderService, never()).onPayment(any(), any());
    }

    @Test
    void paidOrders_areCheckedForRefundsOnlyOnceEverySixHours() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        givenNoUnpaidOrders();
        when(orders.findStalePending(eq(PackOrderStatus.PAID), any(), any())).thenReturn(List.of(paid(42L, "777")));
        Payment approved = mock(Payment.class);
        when(approved.getStatus()).thenReturn("approved");
        when(mercadoPago.getPayment("777")).thenReturn(approved);

        service.reconcilePendingOrders();
        clock.advance(Duration.ofMinutes(15));
        service.reconcilePendingOrders();
        verify(mercadoPago, times(1)).getPayment("777");

        clock.advance(Duration.ofHours(6));
        service.reconcilePendingOrders();
        verify(mercadoPago, times(2)).getPayment("777");
    }

    @Test
    void aPaidOrderWithoutAPaymentId_isSkipped_andOneFailureDoesNotStopTheOthers() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        givenNoUnpaidOrders();
        PackOrder noPaymentId = paid(41L, null);
        PackOrder failing = paid(42L, "111");
        PackOrder refundedOne = paid(43L, "222");
        when(orders.findStalePending(eq(PackOrderStatus.PAID), any(), any())).thenReturn(List.of(noPaymentId, failing, refundedOne));
        when(mercadoPago.getPayment("111")).thenThrow(new IllegalStateException("MP blip"));
        Payment refunded = payment(222L, "refunded");
        when(mercadoPago.getPayment("222")).thenReturn(refunded);

        service.reconcilePendingOrders();

        verify(packOrderService).onPayment(eq(43L), any());
        verify(packOrderService, never()).onPayment(eq(41L), any());
    }

    private void givenNoUnpaidOrders() {
        org.mockito.Mockito.lenient().when(orders.findStalePending(eq(PackOrderStatus.PENDING), any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(orders.findStalePending(eq(PackOrderStatus.FAILED), any(), any())).thenReturn(List.of());
    }

    private static PackOrder paid(long id, String mpPaymentId) {
        PackOrder order = new PackOrder();
        order.setId(id);
        order.setStatus(PackOrderStatus.PAID);
        order.setMpPaymentId(mpPaymentId);
        order.setPaidAt(NOW.minus(Duration.ofDays(2)));
        order.setCreatedAt(NOW.minus(Duration.ofDays(2)));
        return order;
    }

    private static PackOrder stale(long id) {
        PackOrder order = new PackOrder();
        order.setId(id);
        order.setStatus(PackOrderStatus.PENDING);
        order.setCreatedAt(NOW.minus(Duration.ofHours(1)));
        return order;
    }

    private static Payment payment(long id, String status) {
        Payment payment = mock(Payment.class);
        when(payment.getId()).thenReturn(id);
        when(payment.getStatus()).thenReturn(status);
        when(payment.getTransactionAmount()).thenReturn(new BigDecimal("14990.00"));
        when(payment.getCurrencyId()).thenReturn("ARS");
        when(payment.getExternalReference()).thenReturn("pack:" + (id == 777L ? 42 : id / 10));
        return payment;
    }
}
