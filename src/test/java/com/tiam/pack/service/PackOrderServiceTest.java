package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.exception.ResourceNotFoundException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PackOrderServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");

    @Mock PackOrderRepository orders;
    @Mock ChallengePurchaseService challengePurchases;
    @Mock ApplicationEventPublisher events;
    @Mock PackOwnerNotifier ownerNotifier;

    PackProperties properties;
    PackOrderService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        service = new PackOrderService(orders, challengePurchases, events, properties, ownerNotifier,
                new MutableClock(NOW));
    }

    // --- approved ------------------------------------------------------------------------------------

    @Test
    void approvedForTheRightAmount_marksTheOrderPaid_andOpensTheAccessWindow() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        PackOrderStatus result = service.onPayment(42L, approved("PAY-1", "14990.00", "ARS"));

        assertThat(result).isEqualTo(PackOrderStatus.PAID);
        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PAID);
        assertThat(order.getMpPaymentId()).isEqualTo("PAY-1");
        assertThat(order.getPaidAt()).isEqualTo(NOW);
        assertThat(order.getAccessExpiresAt()).isEqualTo(NOW.plus(90, ChronoUnit.DAYS));
        verify(orders).save(order);
    }

    @Test
    void approved_publishesThePaidEventOnce_insideTheTransaction() {
        givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, approved("PAY-1", "14990.00", "ARS"));

        ArgumentCaptor<PackOrderPaidEvent> captor = ArgumentCaptor.forClass(PackOrderPaidEvent.class);
        verify(events, times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(42L);
    }

    @Test
    void approved_theAccessWindowFollowsTheConfiguredDays() {
        properties.setAccessDays(30);
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, approved("PAY-1", "14990.00", "ARS"));

        assertThat(order.getAccessExpiresAt()).isEqualTo(NOW.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void approved_theSameNotificationTwice_isIdempotent_noSecondEventNorSave() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setMpPaymentId("PAY-1");

        PackOrderStatus result = service.onPayment(42L, approved("PAY-1", "14990.00", "ARS"));

        assertThat(result).isEqualTo(PackOrderStatus.PAID);
        verify(orders, never()).save(any());
        verifyNoInteractions(events, challengePurchases, ownerNotifier);
    }

    @Test
    void approved_aSecondDifferentPaymentForAnAlreadyPaidOrder_isNotAppliedButTheOwnerIsTold() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setMpPaymentId("PAY-1");

        service.onPayment(42L, approved("PAY-2", "14990.00", "ARS"));

        assertThat(order.getMpPaymentId()).isEqualTo("PAY-1");
        verify(orders, never()).save(any());
        verifyNoInteractions(events);
        verify(ownerNotifier).notify(anyString(), org.mockito.ArgumentMatchers.contains("PAY-2"));
    }

    @Test
    void approved_aRevokedOrderIsNeverResurrected() {
        PackOrder order = givenOrder(PackOrderStatus.REVOKED, "14990.00");

        service.onPayment(42L, approved("PAY-9", "14990.00", "ARS"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.REVOKED);
        verify(orders, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void approved_afterAnEarlierFailedAttempt_stillPays() {
        PackOrder order = givenOrder(PackOrderStatus.FAILED, "14990.00");

        service.onPayment(42L, approved("PAY-2", "14990.00", "ARS"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PAID);
    }

    // --- amount and currency checks ---------------------------------------------------------------------

    @Test
    void approvedForADifferentAmount_isHeldForReview_nothingIsDelivered_andTheOwnerIsTold() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        PackOrderStatus result = service.onPayment(42L, approved("PAY-1", "100.00", "ARS"));

        assertThat(result).isEqualTo(PackOrderStatus.REVIEW);
        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.REVIEW);
        assertThat(order.getPaidAt()).isNull();
        assertThat(order.getAccessExpiresAt()).isNull();
        verify(orders).save(order);
        verifyNoInteractions(events, challengePurchases);
        verify(ownerNotifier).notify(org.mockito.ArgumentMatchers.contains("42"), org.mockito.ArgumentMatchers.contains("100"));
    }

    @Test
    void approvedInAnotherCurrency_isHeldForReview() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, approved("PAY-1", "14990.00", "USD"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.REVIEW);
        verifyNoInteractions(events);
    }

    @Test
    void approvedWithNoAmount_isHeldForReview() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, new PaymentSnapshot("PAY-1", "approved", null, "ARS", "pack:42"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.REVIEW);
    }

    @Test
    void amountComparisonIsNumeric_notTextual() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, approved("PAY-1", "14990.0", "ARS")); // same money, different scale

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PAID);
    }

    @Test
    void aSlightlyDifferentAmount_isNotGoodEnough() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, approved("PAY-1", "14989.99", "ARS"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.REVIEW);
    }

    @Test
    void reviewHitTwiceByTheSameRetriedNotification_tellsTheOwnerOnlyOnce() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, approved("PAY-1", "100.00", "ARS"));
        service.onPayment(42L, approved("PAY-1", "100.00", "ARS"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.REVIEW);
        verify(ownerNotifier, times(1)).notify(anyString(), anyString());
    }

    @Test
    void anOrderInReview_canStillBePaidByALaterCorrectPayment() {
        PackOrder order = givenOrder(PackOrderStatus.REVIEW, "14990.00");
        order.setMpPaymentId("PAY-BAD");

        service.onPayment(42L, approved("PAY-GOOD", "14990.00", "ARS"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PAID);
        assertThat(order.getMpPaymentId()).isEqualTo("PAY-GOOD");
        verify(events).publishEvent(any(PackOrderPaidEvent.class));
    }

    // --- rejected / cancelled -----------------------------------------------------------------------------

    @Test
    void rejected_marksAPendingOrderFailed() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        PackOrderStatus result = service.onPayment(42L, snapshot("PAY-1", "rejected"));

        assertThat(result).isEqualTo(PackOrderStatus.FAILED);
        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.FAILED);
        verifyNoInteractions(events);
    }

    @Test
    void cancelled_marksAPendingOrderFailed() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, snapshot("PAY-1", "cancelled"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.FAILED);
    }

    @Test
    void rejected_neverDowngradesAPaidOrder() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setMpPaymentId("PAY-1");

        service.onPayment(42L, snapshot("PAY-2", "rejected"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PAID);
        verify(orders, never()).save(any());
    }

    @Test
    void rejected_doesNotTouchOrdersInReviewOrRevoked() {
        PackOrder review = givenOrder(PackOrderStatus.REVIEW, "14990.00");

        service.onPayment(42L, snapshot("PAY-1", "rejected"));

        assertThat(review.getStatus()).isEqualTo(PackOrderStatus.REVIEW);
    }

    // --- refunds and chargebacks ------------------------------------------------------------------------------

    @Test
    void refunded_revokesTheOrder() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setMpPaymentId("PAY-1");

        PackOrderStatus result = service.onPayment(42L, snapshot("PAY-1", "refunded"));

        assertThat(result).isEqualTo(PackOrderStatus.REVOKED);
        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.REVOKED);
        verify(orders).save(order);
    }

    @Test
    void chargedBack_revokesTheOrder() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setMpPaymentId("PAY-1");

        service.onPayment(42L, snapshot("PAY-1", "charged_back"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.REVOKED);
    }

    @Test
    void refunded_alsoClosesTheBundledDesafioMonth() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "24990.00");
        order.setMpPaymentId("PAY-1");
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);

        service.onPayment(42L, snapshot("PAY-1", "refunded"));

        verify(challengePurchases).deactivatePurchase(77L);
    }

    @Test
    void refunded_withoutAGamesAddOn_leavesTheDesafioAlone() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setMpPaymentId("PAY-1");

        service.onPayment(42L, snapshot("PAY-1", "refunded"));

        verifyNoInteractions(challengePurchases);
    }

    @Test
    void refundOfADifferentPaymentThanTheOneThatPaidTheOrder_isIgnored() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setMpPaymentId("PAY-GOOD");

        service.onPayment(42L, snapshot("PAY-OTHER", "refunded"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PAID);
        verify(orders, never()).save(any());
    }

    @Test
    void refundedTwice_isIdempotent() {
        PackOrder order = givenOrder(PackOrderStatus.REVOKED, "14990.00");
        order.setMpPaymentId("PAY-1");
        order.setChallengePurchaseId(77L);

        service.onPayment(42L, snapshot("PAY-1", "refunded"));

        verify(orders, never()).save(any());
        verifyNoInteractions(challengePurchases);
    }

    // --- the games add-on ------------------------------------------------------------------------------------------

    @Test
    void approvedWithGames_createsExactlyOneBundledDesafioMonth_andRemembersIt() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "24990.00");
        order.setWithGames(true);
        order.setPhone("542211234567");
        ChallengePurchase granted = new ChallengePurchase();
        granted.setId(77L);
        when(challengePurchases.grantBundledMonth("Ana Gómez", "542211234567", "ana@example.com"))
                .thenReturn(Optional.of(granted));

        service.onPayment(42L, approved("PAY-1", "24990.00", "ARS"));

        assertThat(order.getChallengePurchaseId()).isEqualTo(77L);
        verify(challengePurchases, times(1)).grantBundledMonth(any(), any(), any());
    }

    @Test
    void approvedWithGames_repeatedNotification_doesNotGrantASecondMonth() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "24990.00");
        order.setWithGames(true);
        order.setPhone("542211234567");
        ChallengePurchase granted = new ChallengePurchase();
        granted.setId(77L);
        when(challengePurchases.grantBundledMonth(any(), any(), any())).thenReturn(Optional.of(granted));

        service.onPayment(42L, approved("PAY-1", "24990.00", "ARS"));
        service.onPayment(42L, approved("PAY-1", "24990.00", "ARS"));
        service.onPayment(42L, approved("PAY-1", "24990.00", "ARS"));

        verify(challengePurchases, times(1)).grantBundledMonth(any(), any(), any());
        verify(events, times(1)).publishEvent(any(PackOrderPaidEvent.class));
    }

    @Test
    void approvedWithGames_whenNoMonthIsLeft_theOrderIsStillPaidAndDeliveredAndTheOwnerIsTold() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "24990.00");
        order.setWithGames(true);
        order.setPhone("542211234567");
        when(challengePurchases.grantBundledMonth(any(), any(), any())).thenReturn(Optional.empty());

        service.onPayment(42L, approved("PAY-1", "24990.00", "ARS"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PAID);
        assertThat(order.getChallengePurchaseId()).isNull();
        verify(events).publishEvent(any(PackOrderPaidEvent.class));
        verify(ownerNotifier).notify(anyString(), org.mockito.ArgumentMatchers.contains("42"));
    }

    @Test
    void approvedWithGames_butNoPhone_theOrderIsStillPaidAndTheOwnerIsTold() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "24990.00");
        order.setWithGames(true);
        order.setPhone(null);

        service.onPayment(42L, approved("PAY-1", "24990.00", "ARS"));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PAID);
        verifyNoInteractions(challengePurchases);
        verify(ownerNotifier).notify(anyString(), anyString());
    }

    @Test
    void approvedWithoutGames_neverTouchesTheDesafio() {
        givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, approved("PAY-1", "14990.00", "ARS"));

        verifyNoInteractions(challengePurchases);
    }

    // --- other statuses and unknown orders ------------------------------------------------------------------------------

    @Test
    void pendingAndInProcessPayments_changeNothing() {
        PackOrder order = givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, snapshot("PAY-1", "pending"));
        service.onPayment(42L, snapshot("PAY-1", "in_process"));
        service.onPayment(42L, snapshot("PAY-1", null));

        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PENDING);
        verify(orders, never()).save(any());
    }

    @Test
    void unknownOrder_isAResourceNotFound_whichTheWebhookAcknowledgesWithoutRetry() {
        when(orders.findWithLockById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.onPayment(99L, approved("PAY-1", "14990.00", "ARS")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theOrderIsAlwaysReadUnderTheRowLock() {
        givenOrder(PackOrderStatus.PENDING, "14990.00");

        service.onPayment(42L, approved("PAY-1", "14990.00", "ARS"));

        verify(orders).findWithLockById(eq(42L));
        verify(orders, never()).findById(any());
        verifyNoMoreInteractions(ownerNotifier);
    }

    // --- re-sending access: rotate the token, renew the window --------------------------------------------------

    @Test
    void rotateAccess_issuesANewToken_andRenewsTheAccessWindow() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setAccessToken("old-token");
        order.setAccessExpiresAt(NOW.plus(3, ChronoUnit.DAYS));

        PackOrderService.AccessRotation rotation = service.rotateAccess(42L).orElseThrow();

        assertThat(order.getAccessToken()).isNotEqualTo("old-token").hasSize(43);
        assertThat(order.getAccessExpiresAt()).isEqualTo(NOW.plus(90, ChronoUnit.DAYS));
        assertThat(rotation.previousToken()).isEqualTo("old-token");
        assertThat(rotation.previousExpiry()).isEqualTo(NOW.plus(3, ChronoUnit.DAYS));
        assertThat(rotation.order()).isSameAs(order);
        verify(orders).save(order);
    }

    @Test
    void rotateAccess_alsoRevivesAnOrderWhoseWindowAlreadyClosed() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setAccessExpiresAt(NOW.minus(10, ChronoUnit.DAYS));

        service.rotateAccess(42L);

        assertThat(order.getAccessExpiresAt()).isEqualTo(NOW.plus(90, ChronoUnit.DAYS));
    }

    @Test
    void rotateAccess_neverTouchesAnOrderThatIsNotPaid() {
        for (PackOrderStatus status : new PackOrderStatus[] {PackOrderStatus.PENDING, PackOrderStatus.FAILED,
                PackOrderStatus.REVIEW, PackOrderStatus.REVOKED}) {
            PackOrder order = givenOrder(status, "14990.00");
            order.setAccessToken("keep-me");

            assertThat(service.rotateAccess(42L)).as("status %s", status).isEmpty();
            assertThat(order.getAccessToken()).isEqualTo("keep-me");
        }
        verify(orders, never()).save(any());
    }

    @Test
    void rotateAccess_doesNotTouchEmailSentAt_soTheFirstEmailLogicIsUnaffected() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        Instant sent = NOW.minus(1, ChronoUnit.DAYS);
        order.setEmailSentAt(sent);

        service.rotateAccess(42L);

        assertThat(order.getEmailSentAt()).isEqualTo(sent);
    }

    @Test
    void restoreAccess_putsTheOldTokenBack_onlyIfTheNewOneIsStillTheCurrentOne() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setAccessToken("new-token");
        order.setAccessExpiresAt(NOW.plus(90, ChronoUnit.DAYS));

        service.restoreAccess(42L, "new-token", "old-token", NOW.plus(3, ChronoUnit.DAYS));

        assertThat(order.getAccessToken()).isEqualTo("old-token");
        assertThat(order.getAccessExpiresAt()).isEqualTo(NOW.plus(3, ChronoUnit.DAYS));
        verify(orders).save(order);
    }

    @Test
    void restoreAccess_whenSomeoneRotatedAgainMeanwhile_leavesTheNewerTokenAlone() {
        PackOrder order = givenOrder(PackOrderStatus.PAID, "14990.00");
        order.setAccessToken("even-newer-token");

        service.restoreAccess(42L, "new-token", "old-token", NOW);

        assertThat(order.getAccessToken()).isEqualTo("even-newer-token");
        verify(orders, never()).save(any());
    }

    // --- helpers ------------------------------------------------------------------------------------------------------------

    private PackOrder givenOrder(PackOrderStatus status, String total) {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setStatus(status);
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        order.setCurrency("ARS");
        order.setTotalAmount(new BigDecimal(total));
        order.setPackAmount(new BigDecimal(total));
        when(orders.findWithLockById(42L)).thenReturn(Optional.of(order));
        return order;
    }

    private static PaymentSnapshot approved(String paymentId, String amount, String currency) {
        return new PaymentSnapshot(paymentId, "approved", new BigDecimal(amount), currency, "pack:42");
    }

    private static PaymentSnapshot snapshot(String paymentId, String status) {
        return new PaymentSnapshot(paymentId, status, new BigDecimal("14990.00"), "ARS", "pack:42");
    }
}
