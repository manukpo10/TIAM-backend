package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    PackOrderReconciliationService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        service = new PackOrderReconciliationService(orders, mercadoPago, packOrderService, properties, new MutableClock(NOW));
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
