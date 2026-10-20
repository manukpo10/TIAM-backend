package com.tiam.challenge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.subscription.service.MercadoPagoService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Which Desafío month a printable product's add-on grants. Both products (the Pack's "mes de juegos" and the Cuadernos
 * offer) go through {@link ChallengePurchaseService#grantBundledMonth}, so the rule is pinned here once:
 * EXACTLY ONE month per order, and it is the lowest month on sale this phone has not PAID, the same rule the Desafío's
 * own checkout uses ({@code nextUnpaidMonth}). Never a month the buyer already owns; nothing (not an exception) when
 * none is left; no automatic renewal.
 */
@ExtendWith(MockitoExtension.class)
class ChallengeBundledMonthSelectionTest {

    static final String PHONE = "542211234567";

    @Mock ChallengePurchaseRepository repository;
    @Mock MercadoPagoService mercadoPagoService;
    @Mock WhatsAppProperties whatsAppProperties;
    @Mock ApplicationEventPublisher eventPublisher;

    ChallengePurchaseService service;

    @BeforeEach
    void setUp() {
        service = new ChallengePurchaseService(repository, mercadoPagoService, whatsAppProperties, eventPublisher);
    }

    @Test
    void aBuyerWhoAlreadyPaidMonthOne_getsMonthTwo_neverMonthOneAgain() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases(ChallengePurchaseStatus.PAID, 1));
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChallengePurchase granted = service.grantBundledMonth("Ana", PHONE, "ana@example.com").orElseThrow();

        assertThat(granted.getChallengeMonth()).isEqualTo(2);
    }

    @Test
    void aFirstTimeBuyer_getsMonthOne() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(List.of());
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com").orElseThrow().getChallengeMonth()).isEqualTo(1);
    }

    @Test
    void aGapInThePaidMonths_isFilledFirst() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases(ChallengePurchaseStatus.PAID, 1, 3));
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com").orElseThrow().getChallengeMonth()).isEqualTo(2);
    }

    @Test
    void aPhoneThatOnlyPaidALaterMonth_getsTheLowestUnpaidOne_likeTheDesafiosOwnCheckout() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases(ChallengePurchaseStatus.PAID, 2));
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com").orElseThrow().getChallengeMonth()).isEqualTo(1);
    }

    @Test
    void theLastMonthOnSale_isGrantedWhenItIsTheOnlyOneLeft() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases(ChallengePurchaseStatus.PAID, 1, 2, 3));
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com").orElseThrow().getChallengeMonth()).isEqualTo(4);
    }

    @Test
    void whenNoMonthIsLeftOnSale_nothingIsGranted_andNothingIsThrown() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases(ChallengePurchaseStatus.PAID, 1, 2, 3, 4));

        assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com")).isEmpty();
        assertThat(service.hasMonthAvailable(PHONE)).isFalse();

        verify(repository, never()).save(any());
    }

    @Test
    void aMonthAheadOfTheLaunch_grantedByHand_isNeitherCountedNorOffered() {
        // month 5 has a catalog but is not on sale: the owner may have minted it manually
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases(ChallengePurchaseStatus.PAID, 1, 2, 3, 4, 5));
        assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com")).isEmpty();

        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases(ChallengePurchaseStatus.PAID, 5));
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com").orElseThrow().getChallengeMonth()).isEqualTo(1);
    }

    @Test
    void onlyPaidMonthsCount_aPendingOrFailedOneDoesNotClaimAMonth() {
        List<ChallengePurchase> existing = new ArrayList<>(purchases(ChallengePurchaseStatus.PAID, 1));
        existing.addAll(purchases(ChallengePurchaseStatus.PENDING, 2));
        existing.addAll(purchases(ChallengePurchaseStatus.FAILED, 3));
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(existing);
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com").orElseThrow().getChallengeMonth()).isEqualTo(2);
    }

    @Test
    void oneGrant_isExactlyOnePaidPurchase_withNoPaymentAndNoRenewalMarker() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(List.of());
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChallengePurchase granted = service.grantBundledMonth("Ana", PHONE, "ana@example.com").orElseThrow();

        verify(repository, times(1)).save(any(ChallengePurchase.class));
        assertThat(granted.getStatus()).isEqualTo(ChallengePurchaseStatus.PAID);
        assertThat(granted.getMpPaymentId()).isNull(); // nothing to charge again: there is no subscription
    }

    @Test
    void theAvailabilityCheck_andTheGrant_agreeOnEveryCombination() {
        int[][] paidSets = {{}, {1}, {2}, {1, 2}, {1, 3}, {1, 2, 3}, {2, 3, 4}, {1, 2, 3, 4}, {1, 2, 3, 4, 5}};
        for (int[] paid : paidSets) {
            when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases(ChallengePurchaseStatus.PAID, paid));
            org.mockito.Mockito.lenient().when(repository.save(any(ChallengePurchase.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            boolean available = service.hasMonthAvailable(PHONE);

            assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com").isPresent())
                    .as("paid=%s", java.util.Arrays.toString(paid)).isEqualTo(available);
        }
    }

    // --- which month an order was actually given (emails and the downloads page say "Mes N del Desafío") -----------------

    @Test
    void findMonthIfActive_isTheMonthOfTheActivePaidPurchase() {
        ChallengePurchase purchase = purchases(ChallengePurchaseStatus.PAID, 3).get(0);
        when(repository.findById(5L)).thenReturn(java.util.Optional.of(purchase));

        assertThat(service.findMonthIfActive(5L)).contains(3);
    }

    @Test
    void findMonthIfActive_isEmptyForAnythingThatIsNotAnActivePaidPurchase() {
        ChallengePurchase pending = purchases(ChallengePurchaseStatus.PENDING, 2).get(0);
        ChallengePurchase closed = purchases(ChallengePurchaseStatus.PAID, 2).get(0);
        closed.setActivo(false);
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(pending));
        when(repository.findById(2L)).thenReturn(java.util.Optional.of(closed));
        when(repository.findById(3L)).thenReturn(java.util.Optional.empty());

        assertThat(service.findMonthIfActive(1L)).isEmpty();
        assertThat(service.findMonthIfActive(2L)).isEmpty();
        assertThat(service.findMonthIfActive(3L)).isEmpty();
        assertThat(service.findMonthIfActive(null)).isEmpty();
    }

    private static List<ChallengePurchase> purchases(ChallengePurchaseStatus status, int... months) {
        List<ChallengePurchase> list = new ArrayList<>();
        for (int month : months) {
            ChallengePurchase purchase = new ChallengePurchase();
            purchase.setStatus(status);
            purchase.setChallengeMonth(month);
            list.add(purchase);
        }
        return list;
    }
}
