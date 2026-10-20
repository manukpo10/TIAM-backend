package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderPaidEvent;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** Confirming the payment of a «Cuadernos TIAM» order: same machinery, product-specific amounts. */
@ExtendWith(MockitoExtension.class)
class CuadernosOrderServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");
    static final String PHONE = "542211234567";

    @Mock PackOrderRepository orders;
    @Mock ChallengePurchaseService challengePurchases;
    @Mock ApplicationEventPublisher events;
    @Mock PackOwnerNotifier ownerNotifier;

    PackOrderService service;

    @BeforeEach
    void setUp() {
        service = new PackOrderService(orders, challengePurchases, events, new PackProperties(), ownerNotifier, new MutableClock(NOW));
    }

    @Test
    void approvedForTheCuadernosPrice_marksTheOrderPaid_opensTheWindow_andPublishesTheEmailEvent() {
        PackOrder order = givenOrder("20000.00");

        PackOrderStatus result = service.onPayment(42L, approved("PAY-1", "20000.00"));

        assertThat(result).isEqualTo(PackOrderStatus.PAID);
        assertThat(order.getMpPaymentId()).isEqualTo("PAY-1");
        assertThat(order.getPaidAt()).isEqualTo(NOW);
        assertThat(order.getAccessExpiresAt()).isEqualTo(NOW.plus(90, ChronoUnit.DAYS));
        verify(events, times(1)).publishEvent(any(PackOrderPaidEvent.class));
        verifyNoInteractions(challengePurchases);
    }

    @Test
    void approvedForADifferentAmount_isHeldForReviewAndNothingIsDelivered() {
        PackOrder order = givenOrder("20000.00");

        PackOrderStatus result = service.onPayment(42L, approved("PAY-1", "14990.00"));

        assertThat(result).isEqualTo(PackOrderStatus.REVIEW);
        assertThat(order.getAccessExpiresAt()).isNull();
        verify(events, never()).publishEvent(any(PackOrderPaidEvent.class));
        verify(ownerNotifier).notify(anyString(), anyString());
    }

    @Test
    void withTheDesafioOffer_theTotalIs35000_andExactlyOneMonthIsGranted() {
        PackOrder order = givenOrder("35000.00");
        order.setWithGames(true);
        order.setPhone(PHONE);
        ChallengePurchase granted = new ChallengePurchase();
        granted.setId(77L);
        when(challengePurchases.grantBundledMonth("Ana Gómez", PHONE, "ana@example.com")).thenReturn(Optional.of(granted));

        PackOrderStatus result = service.onPayment(42L, approved("PAY-1", "35000.00"));

        assertThat(result).isEqualTo(PackOrderStatus.PAID);
        assertThat(order.getChallengePurchaseId()).isEqualTo(77L);
        verify(challengePurchases, times(1)).grantBundledMonth(any(), any(), any());
    }

    @Test
    void withTheDesafioOffer_repeatedNotifications_neverGrantASecondMonth() {
        PackOrder order = givenOrder("35000.00");
        order.setWithGames(true);
        order.setPhone(PHONE);
        ChallengePurchase granted = new ChallengePurchase();
        granted.setId(77L);
        when(challengePurchases.grantBundledMonth(any(), any(), any())).thenReturn(Optional.of(granted));

        service.onPayment(42L, approved("PAY-1", "35000.00"));
        service.onPayment(42L, approved("PAY-1", "35000.00"));
        service.onPayment(42L, approved("PAY-1", "35000.00"));

        verify(challengePurchases, times(1)).grantBundledMonth(any(), any(), any());
        verify(events, times(1)).publishEvent(any(PackOrderPaidEvent.class));
    }

    @Test
    void withTheDesafioOffer_payingTheOldPackGamesTotalOrJustTheCollection_isHeldForReview() {
        PackOrder order = givenOrder("35000.00");
        order.setWithGames(true);
        order.setPhone(PHONE);

        assertThat(service.onPayment(42L, approved("PAY-1", "30000.00"))).isEqualTo(PackOrderStatus.REVIEW);
        verifyNoInteractions(challengePurchases);
    }

    @Test
    void withTheDesafioOffer_whenNoMonthIsLeft_theCuadernosAreStillDelivered_andTheOwnerIsTold() {
        PackOrder order = givenOrder("35000.00");
        order.setWithGames(true);
        order.setPhone(PHONE);
        when(challengePurchases.grantBundledMonth(any(), any(), any())).thenReturn(Optional.empty());

        PackOrderStatus result = service.onPayment(42L, approved("PAY-1", "35000.00"));

        assertThat(result).isEqualTo(PackOrderStatus.PAID);
        assertThat(order.getChallengePurchaseId()).isNull();
        verify(events).publishEvent(any(PackOrderPaidEvent.class));
        verify(ownerNotifier).notify(anyString(), org.mockito.ArgumentMatchers.contains("42"));
    }

    @Test
    void aRefundOfACuadernosOrder_revokesIt_andClosesTheBundledDesafioMonth() {
        PackOrder order = givenOrder("35000.00");
        order.setStatus(PackOrderStatus.PAID);
        order.setMpPaymentId("PAY-1");
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);

        PackOrderStatus result = service.onPayment(42L,
                new PaymentSnapshot("PAY-1", "refunded", new BigDecimal("35000.00"), "ARS", "pack:42"));

        assertThat(result).isEqualTo(PackOrderStatus.REVOKED);
        verify(challengePurchases).deactivatePurchase(77L);
    }

    @Test
    void aRejectedPayment_failsAPendingCuadernosOrder() {
        givenOrder("20000.00");

        PackOrderStatus result = service.onPayment(42L,
                new PaymentSnapshot("PAY-1", "rejected", new BigDecimal("20000.00"), "ARS", "pack:42"));

        assertThat(result).isEqualTo(PackOrderStatus.FAILED);
    }

    private PackOrder givenOrder(String total) {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setStatus(PackOrderStatus.PENDING);
        order.setEdition(PackEdition.CUADERNOS);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        order.setCurrency("ARS");
        order.setTotalAmount(new BigDecimal(total));
        order.setPackAmount(new BigDecimal("20000.00"));
        when(orders.findWithLockById(42L)).thenReturn(Optional.of(order));
        return order;
    }

    private static PaymentSnapshot approved(String paymentId, String amount) {
        return new PaymentSnapshot(paymentId, "approved", new BigDecimal(amount), "ARS", "pack:42");
    }
}
