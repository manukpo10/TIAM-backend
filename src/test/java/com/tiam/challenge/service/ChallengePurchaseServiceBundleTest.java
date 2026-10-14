package com.tiam.challenge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.common.exception.BadRequestException;
import com.tiam.subscription.service.MercadoPagoService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The parts of {@link ChallengePurchaseService} that the printable-pack sale relies on (the games
 * add-on is a Desafío month granted with the pack). Kept apart from ChallengePurchaseServiceTest so the
 * Desafío's own tests stay exactly as they were.
 */
@ExtendWith(MockitoExtension.class)
class ChallengePurchaseServiceBundleTest {

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

    // --- hasMonthAvailable ----------------------------------------------------------------------

    @Test
    void hasMonthAvailable_aNewPhoneCanBuy() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(List.of());

        assertThat(service.hasMonthAvailable("221 123-4567")).isTrue();
    }

    @Test
    void hasMonthAvailable_someMonthsPaid_stillAvailable() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(paidMonths(1, 2, 3));

        assertThat(service.hasMonthAvailable(PHONE)).isTrue();
    }

    @Test
    void hasMonthAvailable_everyMonthOnSalePaid_notAvailable() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(paidMonths(1, 2, 3, 4));

        assertThat(service.hasMonthAvailable(PHONE)).isFalse();
    }

    @Test
    void hasMonthAvailable_aPendingMonthDoesNotCount() {
        List<ChallengePurchase> purchases = new ArrayList<>(paidMonths(1, 2, 3));
        purchases.add(purchase(4, ChallengePurchaseStatus.PENDING));
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(purchases);

        assertThat(service.hasMonthAvailable(PHONE)).isTrue();
    }

    // --- grantBundledMonth ------------------------------------------------------------------------

    @Test
    void grantBundledMonth_aNewPhoneGetsMonthOne_asAPaidPurchaseWithoutAMercadoPagoPayment() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(List.of());
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChallengePurchase granted = service.grantBundledMonth("Ana Gómez", "221 123-4567", "ana@example.com").orElseThrow();

        assertThat(granted.getStatus()).isEqualTo(ChallengePurchaseStatus.PAID);
        assertThat(granted.getChallengeMonth()).isEqualTo(1);
        assertThat(granted.getBuyerName()).isEqualTo("Ana Gómez");
        assertThat(granted.getPhone()).isEqualTo(PHONE);
        assertThat(granted.getEmail()).isEqualTo("ana@example.com");
        assertThat(granted.getAccessToken()).isNotBlank();
        assertThat(granted.getPurchaseDate()).isNotNull();
        assertThat(granted.getMpPaymentId()).isNull(); // there is no Mercado Pago payment "of the Desafío"
    }

    @Test
    void grantBundledMonth_picksTheNextMonthThePhoneHasNotPaid() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(paidMonths(1, 2));
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ChallengePurchase granted = service.grantBundledMonth("Ana", PHONE, "ana@example.com").orElseThrow();

        assertThat(granted.getChallengeMonth()).isEqualTo(3);
    }

    @Test
    void grantBundledMonth_doesNotFireTheDesafioPaidEvent_soTheBuyerGetsOneEmailNotTwo() {
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(List.of());
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.grantBundledMonth("Ana", PHONE, "ana@example.com");

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void grantBundledMonth_whenEveryMonthOnSaleIsAlreadyPaid_returnsEmptyInsteadOfThrowing() {
        // Throwing here would poison the pack order's payment transaction (rollback-only).
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(paidMonths(1, 2, 3, 4));

        assertThat(service.grantBundledMonth("Ana", PHONE, "ana@example.com")).isEmpty();

        verify(repository, never()).save(any());
    }

    @Test
    void grantBundledMonth_withoutAUsablePhone_isRefused() {
        assertThatThrownBy(() -> service.grantBundledMonth("Ana", "12", "ana@example.com"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.grantBundledMonth("Ana", null, "ana@example.com"))
                .isInstanceOf(BadRequestException.class);
    }

    // --- deactivate and link -----------------------------------------------------------------------

    @Test
    void deactivate_closesThePurchaseWithoutDeletingIt() {
        ChallengePurchase purchase = purchase(1, ChallengePurchaseStatus.PAID);
        when(repository.findById(5L)).thenReturn(Optional.of(purchase));

        service.deactivatePurchase(5L);

        assertThat(purchase.isActivo()).isFalse();
        verify(repository).save(purchase);
    }

    @Test
    void deactivate_anUnknownPurchase_isANoOp() {
        when(repository.findById(5L)).thenReturn(Optional.empty());

        service.deactivatePurchase(5L);

        verify(repository, never()).save(any());
    }

    @Test
    void findAccessLink_activePaidPurchase_returnsTheDesafioLink() {
        ChallengePurchase purchase = purchase(1, ChallengePurchaseStatus.PAID);
        purchase.setAccessToken("abc-123");
        when(repository.findById(5L)).thenReturn(Optional.of(purchase));
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn("https://tiamdigital.com/desafio/");

        assertThat(service.findAccessLinkIfActive(5L)).contains("https://tiamdigital.com/desafio/abc-123");
    }

    @Test
    void findAccessLink_inactiveOrUnpaidOrUnknown_returnsNothing() {
        ChallengePurchase inactive = purchase(1, ChallengePurchaseStatus.PAID);
        inactive.setActivo(false);
        ChallengePurchase pending = purchase(1, ChallengePurchaseStatus.PENDING);
        when(repository.findById(1L)).thenReturn(Optional.of(inactive));
        when(repository.findById(2L)).thenReturn(Optional.of(pending));
        when(repository.findById(3L)).thenReturn(Optional.empty());

        assertThat(service.findAccessLinkIfActive(1L)).isEmpty();
        assertThat(service.findAccessLinkIfActive(2L)).isEmpty();
        assertThat(service.findAccessLinkIfActive(3L)).isEmpty();
        assertThat(service.findAccessLinkIfActive(null)).isEmpty();
    }

    static List<ChallengePurchase> paidMonths(int... months) {
        List<ChallengePurchase> result = new ArrayList<>();
        for (int month : months) {
            result.add(purchase(month, ChallengePurchaseStatus.PAID));
        }
        return result;
    }

    static ChallengePurchase purchase(int month, ChallengePurchaseStatus status) {
        ChallengePurchase purchase = new ChallengePurchase();
        purchase.setBuyerName("Ana");
        purchase.setPhone(PHONE);
        purchase.setStatus(status);
        purchase.setChallengeMonth(month);
        return purchase;
    }
}
