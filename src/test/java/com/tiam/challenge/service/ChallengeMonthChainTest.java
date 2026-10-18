package com.tiam.challenge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.dto.AdminGrantPurchaseRequest;
import com.tiam.challenge.dto.AdminGrantPurchaseResponse;
import com.tiam.challenge.dto.ChallengeAccessResponse;
import com.tiam.challenge.dto.ChallengeMonthAccess;
import com.tiam.challenge.dto.CreatePurchaseResponse;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.subscription.service.MercadoPagoService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A buyer keeps ONE link for the whole Desafío: month 2 and later are separate purchases (own start date, own
 * progress) chained to the first purchase through {@code parentPurchaseId}, and every one of them is reached from
 * the first purchase's link. Near the end of a month the player offers the next one with a single tap: the buyer's
 * WhatsApp and email already on file are reused (nothing is typed) and Mercado Pago sends them back to their own link.
 */
@ExtendWith(MockitoExtension.class)
class ChallengeMonthChainTest {

    static final String ROOT_TOKEN = "root-token";
    static final String CHILD_TOKEN = "child-token";
    static final String PHONE = "541122334455";
    static final String PLAY_BASE = "https://www.tiamdigital.com/desafio";

    @Mock ChallengePurchaseRepository repository;
    @Mock MercadoPagoService mercadoPagoService;
    @Mock WhatsAppProperties whatsAppProperties;
    @Mock ApplicationEventPublisher eventPublisher;

    ChallengePurchaseService service;

    @BeforeEach
    void setUp() {
        service = new ChallengePurchaseService(repository, mercadoPagoService, whatsAppProperties, eventPublisher);
    }

    // --- getAccess: one link, every month the buyer owns -------------------------------------------

    @Test
    void getAccess_singleMonthBuyer_listsOnlyThatMonth_andOffersTheNextOne() {
        ChallengePurchase root = root(1);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root);

        ChallengeAccessResponse response = service.getAccess(ROOT_TOKEN);

        assertThat(response.challengeMonth()).isEqualTo(1);
        assertThat(response.months()).extracting(ChallengeMonthAccess::challengeMonth).containsExactly(1);
        assertThat(response.months().get(0).accessToken()).isEqualTo(ROOT_TOKEN);
        assertThat(response.months().get(0).totalDays()).isEqualTo(30);
        assertThat(response.nextMonth()).isEqualTo(2);
        assertThat(response.nextMonthPriceArs()).isEqualTo(20000);
    }

    @Test
    void getAccess_withMonthTwoBought_listsBothMonths_andOffersTheOneAfter() {
        ChallengePurchase root = root(1);
        ChallengePurchase child = child(2, ChallengePurchaseStatus.PAID);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, child);
        givenPhoneOwns(PHONE, root, child);

        ChallengeAccessResponse response = service.getAccess(ROOT_TOKEN);

        assertThat(response.challengeMonth()).as("the fields of the token that was opened").isEqualTo(1);
        assertThat(response.months()).extracting(ChallengeMonthAccess::challengeMonth).containsExactly(1, 2);
        assertThat(response.months()).extracting(ChallengeMonthAccess::accessToken)
                .containsExactly(ROOT_TOKEN, CHILD_TOKEN);
        assertThat(response.nextMonth()).isEqualTo(3);
    }

    @Test
    void getAccess_openedWithAMonthsOwnToken_stillReturnsTheWholeChain() {
        ChallengePurchase root = root(1);
        ChallengePurchase child = child(2, ChallengePurchaseStatus.PAID);
        givenToken(CHILD_TOKEN, child);
        when(repository.findById(1L)).thenReturn(Optional.of(root));
        givenChildren(root, child);
        givenPhoneOwns(PHONE, root, child);

        ChallengeAccessResponse response = service.getAccess(CHILD_TOKEN);

        assertThat(response.challengeMonth()).isEqualTo(2);
        assertThat(response.months()).extracting(ChallengeMonthAccess::challengeMonth).containsExactly(1, 2);
    }

    @Test
    void getAccess_unpaidNextMonth_isNotListed_andIsOfferedAgain() {
        ChallengePurchase root = root(1);
        ChallengePurchase abandoned = child(2, ChallengePurchaseStatus.PENDING);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, abandoned);
        givenPhoneOwns(PHONE, root);

        ChallengeAccessResponse response = service.getAccess(ROOT_TOKEN);

        assertThat(response.months()).extracting(ChallengeMonthAccess::challengeMonth).containsExactly(1);
        assertThat(response.nextMonth()).isEqualTo(2);
    }

    @Test
    void getAccess_lastMonthOnSale_offersNothing() {
        ChallengePurchase root = root(4);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);

        ChallengeAccessResponse response = service.getAccess(ROOT_TOKEN);

        assertThat(response.nextMonth()).isNull();
        assertThat(response.nextMonthPriceArs()).isNull();
    }

    @Test
    void getAccess_nextMonthAlreadyOwnedByPhone_offersNothing() {
        ChallengePurchase root = root(1);
        ChallengePurchase boughtSeparately = root(2);
        boughtSeparately.setAccessToken("separate-token");
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root, boughtSeparately);

        assertThat(service.getAccess(ROOT_TOKEN).nextMonth()).isNull();
    }

    /** Two taps that both got paid: one month, one tab, and it is the first purchase of that month. */
    @Test
    void getAccess_twoPaidRowsForTheSameMonth_showTheMonthOnce() {
        ChallengePurchase root = root(1);
        ChallengePurchase first = child(2, ChallengePurchaseStatus.PAID);
        first.setPurchaseDate(Instant.now().minus(3, ChronoUnit.DAYS));
        ChallengePurchase duplicate = child(2, ChallengePurchaseStatus.PAID);
        duplicate.setId(3L);
        duplicate.setAccessToken("duplicate-token");
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, duplicate, first);
        givenPhoneOwns(PHONE, root, first, duplicate);

        ChallengeAccessResponse response = service.getAccess(ROOT_TOKEN);

        assertThat(response.months()).extracting(ChallengeMonthAccess::challengeMonth).containsExactly(1, 2);
        assertThat(response.months().get(1).accessToken()).isEqualTo(CHILD_TOKEN);
    }

    /** A refunded first purchase is deactivated: the month bought from the player must not hang from a dead link. */
    @Test
    void getAccess_firstPurchaseDeactivated_theLaterMonthStandsOnItsOwn() {
        ChallengePurchase root = root(1);
        root.setActivo(false);
        ChallengePurchase child = child(2, ChallengePurchaseStatus.PAID);
        givenToken(CHILD_TOKEN, child);
        when(repository.findById(1L)).thenReturn(Optional.of(root));
        givenChildren(child);
        when(repository.findByParentPurchaseIdAndActivoTrue(2L)).thenReturn(List.of());
        givenPhoneOwns(PHONE, child);

        ChallengeAccessResponse response = service.getAccess(CHILD_TOKEN);

        assertThat(response.months()).extracting(ChallengeMonthAccess::challengeMonth).containsExactly(2);
        assertThat(response.months().get(0).accessToken()).isEqualTo(CHILD_TOKEN);
    }

    // --- createNextMonthPurchase: the player's "Renovar y desbloquear el mes siguiente" ------------

    @Test
    void createNextMonthPurchase_createsAPendingNextMonthLinkedToTheFirstPurchase_withTheContactOnFile() throws Exception {
        ChallengePurchase root = root(1);
        root.setBuyerName("Amelia Gómez");
        root.setEmail("amelia@example.com");
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();

        CreatePurchaseResponse response = service.createNextMonthPurchase(ROOT_TOKEN, 2);

        assertThat(response.checkoutUrl()).isEqualTo("https://mp.example/checkout");
        ArgumentCaptor<ChallengePurchase> saved = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(repository).save(saved.capture());
        ChallengePurchase created = saved.getValue();
        assertThat(created.getStatus()).isEqualTo(ChallengePurchaseStatus.PENDING);
        assertThat(created.getChallengeMonth()).isEqualTo(2);
        assertThat(created.getParentPurchaseId()).isEqualTo(1L);
        assertThat(created.getBuyerName()).isEqualTo("Amelia Gómez");
        assertThat(created.getPhone()).as("the number of the purchase: the bot finds the buyer by it").isEqualTo(PHONE);
        assertThat(created.getEmail()).isEqualTo("amelia@example.com");
        assertThat(created.getAccessToken()).isNotEqualTo(ROOT_TOKEN).isNotBlank();
        verify(mercadoPagoService).createPreference(eq("Desafío 30 días - TIAM Digital - Mes 2"),
                eq(BigDecimal.valueOf(20000)), eq("amelia@example.com"), eq("77"));
    }

    @Test
    void createNextMonthPurchase_withoutAnEmailOnFile_chargesWithoutAPayerEmail() throws Exception {
        ChallengePurchase root = root(1);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();

        service.createNextMonthPurchase(ROOT_TOKEN, null);

        ArgumentCaptor<ChallengePurchase> saved = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isNull();
        verify(mercadoPagoService).createPreference(anyString(), any(BigDecimal.class), eq(null), eq("77"));
    }

    @Test
    void createNextMonthPurchase_contactOnFile_comesFromTheNewestMonth() throws Exception {
        ChallengePurchase root = root(1);
        root.setEmail("vieja@example.com");
        ChallengePurchase child = child(2, ChallengePurchaseStatus.PAID);
        child.setPhone("541199887766");
        child.setEmail("reciente@example.com");
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, child);
        givenPhoneOwns(PHONE, root);
        givenPhoneOwns("541199887766", child);
        givenCheckoutWorks();

        service.createNextMonthPurchase(ROOT_TOKEN, 3);

        ArgumentCaptor<ChallengePurchase> saved = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPhone()).isEqualTo("541199887766");
        assertThat(saved.getValue().getEmail()).isEqualTo("reciente@example.com");
    }

    @Test
    void createNextMonthPurchase_fromAMonthsOwnToken_continuesTheSameChain() throws Exception {
        ChallengePurchase root = root(1);
        ChallengePurchase child = child(2, ChallengePurchaseStatus.PAID);
        givenToken(CHILD_TOKEN, child);
        when(repository.findById(1L)).thenReturn(Optional.of(root));
        givenChildren(root, child);
        givenPhoneOwns(PHONE, root, child);
        givenCheckoutWorks();

        service.createNextMonthPurchase(CHILD_TOKEN, null);

        ArgumentCaptor<ChallengePurchase> saved = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getChallengeMonth()).isEqualTo(3);
        assertThat(saved.getValue().getParentPurchaseId()).as("always the first purchase").isEqualTo(1L);
    }

    /** Back from Mercado Pago the buyer lands on their own link, where the new month appears. */
    @Test
    void createNextMonthPurchase_sendsTheBuyerBackToTheirOwnLink() throws Exception {
        ChallengePurchase root = root(1);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn(PLAY_BASE + "/");

        service.createNextMonthPurchase(ROOT_TOKEN, 2);

        verify(mercadoPagoService).createPreference(anyString(), any(BigDecimal.class), eq(null), eq("77"),
                eq(PLAY_BASE + "/" + ROOT_TOKEN));
    }

    /** A base that is not public https (local development) cannot be a Mercado Pago return address. */
    @Test
    void createNextMonthPurchase_withoutAPublicHttpsLinkBase_usesTheDefaultReturnPage() throws Exception {
        ChallengePurchase root = root(1);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn("http://localhost:5173/desafio");

        service.createNextMonthPurchase(ROOT_TOKEN, 2);

        verify(mercadoPagoService).createPreference(anyString(), any(BigDecimal.class), eq(null), eq("77"));
    }

    /** A stale page (back button after paying) still offers month 2: it must never charge month 3 by surprise. */
    @Test
    void createNextMonthPurchase_forAMonthThatIsNoLongerTheNextOne_isRejectedAndNothingIsSaved() {
        ChallengePurchase root = root(1);
        ChallengePurchase child = child(2, ChallengePurchaseStatus.PAID);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, child);
        givenPhoneOwns(PHONE, root, child);

        assertThatThrownBy(() -> service.createNextMonthPurchase(ROOT_TOKEN, 2))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    /** Tapping twice while the first payment is still pending must not pile up rows: the pending one is reused. */
    @Test
    void createNextMonthPurchase_reusesAPendingRowOfThatMonth_insteadOfCreatingAnother() throws Exception {
        ChallengePurchase root = root(1);
        root.setEmail("amelia@example.com");
        ChallengePurchase pending = child(2, ChallengePurchaseStatus.PENDING);
        pending.setId(5L);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, pending);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();

        service.createNextMonthPurchase(ROOT_TOKEN, 2);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(p -> p != pending));
        verify(mercadoPagoService).createPreference(anyString(), any(BigDecimal.class), eq("amelia@example.com"), eq("5"));
    }

    @Test
    void createNextMonthPurchase_noMonthLeftToBuy_isRejectedAndNothingIsSaved() {
        ChallengePurchase root = root(4);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);

        assertThatThrownBy(() -> service.createNextMonthPurchase(ROOT_TOKEN, null))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createNextMonthPurchase_withNoPhoneOnFile_isRejectedAndNothingIsSaved() {
        ChallengePurchase root = root(1);
        root.setPhone(" ");
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);

        assertThatThrownBy(() -> service.createNextMonthPurchase(ROOT_TOKEN, null))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void createNextMonthPurchase_unknownOrUnpaidToken_isNotFound() {
        when(repository.findByAccessTokenAndActivoTrue("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createNextMonthPurchase("nope", null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    // --- what the buyer reads when the tap fails: Spanish, and never Mercado Pago's own words -----------

    @Test
    void createNextMonthPurchase_whenMpRefusesToOpenTheCheckout_saysSoInSpanishWithoutLeakingTheProviderMessage() throws Exception {
        ChallengePurchase root = root(1);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();
        when(mercadoPagoService.createPreference(anyString(), any(BigDecimal.class), any(), anyString()))
                .thenThrow(new MPException("invalid access_token APP_USR-123"));

        assertThatThrownBy(() -> service.createNextMonthPurchase(ROOT_TOKEN, 2))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("No pudimos abrir Mercado Pago. Probá de nuevo en un momento.");
    }

    @Test
    void createNextMonthPurchase_whenPaymentsAreNotConfigured_saysSoInSpanish() {
        ChallengePurchase root = root(1);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root);
        when(mercadoPagoService.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.createNextMonthPurchase(ROOT_TOKEN, 2))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("El pago online no está disponible por ahora. Probá de nuevo en un rato.");
    }

    // --- a payment already in flight must never be charged twice ----------------------------------------

    /** The webhook was lost: Mercado Pago already approved the pending month, so the buyer is sent to their link, not charged. */
    @Test
    void createNextMonthPurchase_pendingRowAlreadyApprovedInMp_marksItPaid_andSendsTheBuyerToTheirLinkWithoutCharging()
            throws Exception {
        ChallengePurchase root = root(1);
        ChallengePurchase pending = child(2, ChallengePurchaseStatus.PENDING);
        pending.setId(5L);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, pending);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn(PLAY_BASE);
        givenMpPayment("5", 999L, "approved");
        lenient().when(repository.findWithLockById(1L)).thenReturn(Optional.of(root));
        when(repository.findWithLockById(5L)).thenReturn(Optional.of(pending));

        CreatePurchaseResponse response = service.createNextMonthPurchase(ROOT_TOKEN, 2);

        assertThat(response.checkoutUrl()).isEqualTo(PLAY_BASE + "/" + ROOT_TOKEN + "?status=approved");
        assertThat(pending.getStatus()).isEqualTo(ChallengePurchaseStatus.PAID);
        assertThat(pending.getMpPaymentId()).isEqualTo("999");
        verify(mercadoPagoService, never()).createPreference(anyString(), any(BigDecimal.class), any(), anyString());
        verify(mercadoPagoService, never())
                .createPreference(anyString(), any(BigDecimal.class), any(), anyString(), anyString());
    }

    @Test
    void createNextMonthPurchase_approvedInMpWithoutAPublicLinkBase_sendsTheBuyerToTheRelativePlayerRoute() throws Exception {
        ChallengePurchase root = root(1);
        ChallengePurchase pending = child(2, ChallengePurchaseStatus.PENDING);
        pending.setId(5L);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, pending);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();
        givenMpPayment("5", 999L, "approved");
        lenient().when(repository.findWithLockById(1L)).thenReturn(Optional.of(root));
        when(repository.findWithLockById(5L)).thenReturn(Optional.of(pending));

        CreatePurchaseResponse response = service.createNextMonthPurchase(ROOT_TOKEN, 2);

        assertThat(response.checkoutUrl()).isEqualTo("/desafio/" + ROOT_TOKEN + "?status=approved");
    }

    @Test
    void createNextMonthPurchase_pendingRowWithAPaymentStillInProcess_isRefusedAndNothingIsCharged() throws Exception {
        for (String status : List.of("in_process", "pending", "authorized", "in_mediation")) {
            ChallengePurchase root = root(1);
            ChallengePurchase pending = child(2, ChallengePurchaseStatus.PENDING);
            pending.setId(5L);
            givenToken(ROOT_TOKEN, root);
            givenChildren(root, pending);
            givenPhoneOwns(PHONE, root);
            givenCheckoutWorks();
            givenMpPayment("5", 999L, status);

            assertThatThrownBy(() -> service.createNextMonthPurchase(ROOT_TOKEN, 2))
                    .as("payment status %s", status)
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("en proceso");
        }
        verify(mercadoPagoService, never()).createPreference(anyString(), any(BigDecimal.class), any(), anyString());
        verify(mercadoPagoService, never())
                .createPreference(anyString(), any(BigDecimal.class), any(), anyString(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void createNextMonthPurchase_pendingRowWhoseLastPaymentWasRejected_letsTheBuyerTryAgain() throws Exception {
        ChallengePurchase root = root(1);
        ChallengePurchase pending = child(2, ChallengePurchaseStatus.PENDING);
        pending.setId(5L);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, pending);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();
        givenMpPayment("5", 999L, "rejected");

        CreatePurchaseResponse response = service.createNextMonthPurchase(ROOT_TOKEN, 2);

        assertThat(response.checkoutUrl()).isEqualTo("https://mp.example/checkout");
        verify(mercadoPagoService).createPreference(anyString(), any(BigDecimal.class), any(), eq("5"));
    }

    @Test
    void createNextMonthPurchase_pendingRowWithNoPaymentYet_chargesAsUsual() throws Exception {
        ChallengePurchase root = root(1);
        ChallengePurchase pending = child(2, ChallengePurchaseStatus.PENDING);
        pending.setId(5L);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, pending);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();
        when(mercadoPagoService.findLatestPaymentByExternalReference("5")).thenReturn(Optional.empty());

        service.createNextMonthPurchase(ROOT_TOKEN, 2);

        verify(mercadoPagoService).createPreference(anyString(), any(BigDecimal.class), any(), eq("5"));
    }

    /** If Mercado Pago cannot say whether the buyer already paid, charging again is the one wrong answer. */
    @Test
    void createNextMonthPurchase_whenMpCannotConfirmThePreviousPayment_isRefusedAndNothingIsCharged() throws Exception {
        ChallengePurchase root = root(1);
        ChallengePurchase pending = child(2, ChallengePurchaseStatus.PENDING);
        pending.setId(5L);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root, pending);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();
        when(mercadoPagoService.findLatestPaymentByExternalReference("5")).thenThrow(new MPException("MP API blip"));

        assertThatThrownBy(() -> service.createNextMonthPurchase(ROOT_TOKEN, 2))
                .isInstanceOf(BadRequestException.class);
        verify(mercadoPagoService, never()).createPreference(anyString(), any(BigDecimal.class), any(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void createNextMonthPurchase_withNoPendingRow_neverAsksMpForAPreviousPayment() throws Exception {
        ChallengePurchase root = root(1);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();

        service.createNextMonthPurchase(ROOT_TOKEN, 2);

        verify(mercadoPagoService, never()).findLatestPaymentByExternalReference(anyString());
    }

    /** Two taps at once (two tabs, a double tap) queue up on the buyer's first purchase instead of both creating a row. */
    @Test
    void createNextMonthPurchase_locksTheBuyersFirstPurchaseWhileItWorks() throws Exception {
        ChallengePurchase root = root(1);
        givenToken(ROOT_TOKEN, root);
        givenChildren(root);
        givenPhoneOwns(PHONE, root);
        givenCheckoutWorks();

        service.createNextMonthPurchase(ROOT_TOKEN, 2);

        verify(repository).findWithLockById(1L);
    }

    // --- deactivating the first purchase must not break the chain behind it ---------------------------

    /** A refunded first purchase is gone, but the months the buyer paid for later keep one shared link (the lowest becomes the head). */
    @Test
    void deactivatePurchase_ofAFirstPurchaseWithPaidMonthsBehindIt_makesTheLowestPaidMonthTheNewHead() {
        ChallengePurchase root = root(1);
        ChallengePurchase second = child(2, ChallengePurchaseStatus.PAID);
        second.setId(2L);
        ChallengePurchase third = child(3, ChallengePurchaseStatus.PAID);
        third.setId(3L);
        third.setAccessToken("third-token");
        when(repository.findById(1L)).thenReturn(Optional.of(root));
        // newest first on purpose: the choice must not depend on the order the database returns
        when(repository.findByParentPurchaseIdAndActivoTrue(1L)).thenReturn(List.of(third, second));

        service.deactivatePurchase(1L);

        assertThat(root.isActivo()).isFalse();
        assertThat(second.getParentPurchaseId()).as("the new head has no parent").isNull();
        assertThat(third.getParentPurchaseId()).as("the rest now hang from the new head").isEqualTo(2L);
        verify(repository).save(root);
        verify(repository).save(second);
        verify(repository).save(third);
    }

    @Test
    void deactivatePurchase_ofAFirstPurchaseWhoseLaterMonthsWereNeverPaid_leavesThemAlone() {
        ChallengePurchase root = root(1);
        ChallengePurchase unpaid = child(2, ChallengePurchaseStatus.PENDING);
        when(repository.findById(1L)).thenReturn(Optional.of(root));
        when(repository.findByParentPurchaseIdAndActivoTrue(1L)).thenReturn(List.of(unpaid));

        service.deactivatePurchase(1L);

        assertThat(root.isActivo()).isFalse();
        assertThat(unpaid.getParentPurchaseId()).isEqualTo(1L);
        verify(repository, never()).save(unpaid);
    }

    @Test
    void deactivatePurchase_ofALaterMonth_touchesNothingElse() {
        ChallengePurchase child = child(2, ChallengePurchaseStatus.PAID);
        when(repository.findById(2L)).thenReturn(Optional.of(child));
        when(repository.findByParentPurchaseIdAndActivoTrue(2L)).thenReturn(List.of());

        service.deactivatePurchase(2L);

        assertThat(child.isActivo()).isFalse();
        verify(repository).save(child);
        verify(repository, never()).save(org.mockito.ArgumentMatchers.argThat(p -> p != child));
    }

    // --- WhatsApp: the reply always carries the one link ----------------------------------------------

    @Test
    void whatsAppReply_forAChainedMonth_carriesTheFirstPurchasesLink_andListsTheMonths() {
        ChallengePurchase root = root(1);
        ChallengePurchase child = child(2, ChallengePurchaseStatus.PAID);
        child.setPhone("541133445566");
        when(repository.findByPhoneAndActivoTrue("541133445566")).thenReturn(List.of(child));
        when(repository.findById(1L)).thenReturn(Optional.of(root));
        when(repository.findByParentPurchaseIdAndActivoTrue(1L)).thenReturn(List.of(child));
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn(PLAY_BASE);

        String reply = service.buildWhatsAppReply("+54 9 11 3344-5566");

        assertThat(reply).contains(PLAY_BASE + "/" + ROOT_TOKEN).doesNotContain(CHILD_TOKEN);
        assertThat(reply).as("which month would 'Día 28 de 30' be about?").doesNotContain("Día");
        assertThat(reply).contains("meses 1 y 2");
    }

    @Test
    void whatsAppReply_forAFirstPurchase_keepsItsOwnLinkAndTheUsualText() {
        ChallengePurchase root = root(1);
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(List.of(root));
        when(repository.findByParentPurchaseIdAndActivoTrue(1L)).thenReturn(List.of());
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn(PLAY_BASE);

        String reply = service.buildWhatsAppReply("+54 9 11 2233-4455");

        assertThat(reply).contains(PLAY_BASE + "/" + ROOT_TOKEN).contains("Día 28 de 30");
    }

    // --- manual grants join the chain too (most buyers pay by transfer) -------------------------------

    @Test
    void grantManualPurchase_aLaterMonthForAPhoneThatHasAFirstPurchase_joinsItsChain_andHandsOverTheFirstLink() {
        ChallengePurchase root = root(1);
        when(repository.findByPhoneAndActivoTrue(PHONE)).thenReturn(List.of(root));
        when(repository.findById(1L)).thenReturn(Optional.of(root));
        when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> {
            ChallengePurchase saved = invocation.getArgument(0);
            saved.setId(9L);
            return saved;
        });
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn(PLAY_BASE);

        AdminGrantPurchaseResponse response =
                service.grantManualPurchase(new AdminGrantPurchaseRequest("Amelia", "11 2233-4455", null, 2));

        ArgumentCaptor<ChallengePurchase> saved = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getParentPurchaseId()).isEqualTo(1L);
        assertThat(saved.getValue().getChallengeMonth()).isEqualTo(2);
        assertThat(response.playLink()).isEqualTo(PLAY_BASE + "/" + ROOT_TOKEN);
        assertThat(response.accessToken()).isEqualTo(ROOT_TOKEN);
    }

    // --- fixtures ----------------------------------------------------------------------------------------

    private ChallengePurchase root(int month) {
        ChallengePurchase p = new ChallengePurchase();
        p.setId(1L);
        p.setBuyerName("Amelia Gómez");
        p.setPhone(PHONE);
        p.setStatus(ChallengePurchaseStatus.PAID);
        p.setAccessToken(ROOT_TOKEN);
        p.setChallengeMonth(month);
        p.setPurchaseDate(Instant.now().minus(22, ChronoUnit.DAYS));
        return p;
    }

    private ChallengePurchase child(int month, ChallengePurchaseStatus status) {
        ChallengePurchase p = new ChallengePurchase();
        p.setId(2L);
        p.setBuyerName("Amelia Gómez");
        p.setPhone(PHONE);
        p.setStatus(status);
        p.setAccessToken(CHILD_TOKEN);
        p.setChallengeMonth(month);
        p.setParentPurchaseId(1L);
        p.setPurchaseDate(status == ChallengePurchaseStatus.PAID ? Instant.now() : null);
        return p;
    }

    private void givenToken(String token, ChallengePurchase purchase) {
        when(repository.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(purchase));
    }

    private void givenChildren(ChallengePurchase head, ChallengePurchase... children) {
        when(repository.findByParentPurchaseIdAndActivoTrue(head.getId())).thenReturn(List.of(children));
    }

    private void givenPhoneOwns(String phone, ChallengePurchase... purchases) {
        when(repository.findByPhoneAndActivoTrue(phone)).thenReturn(List.of(purchases));
    }

    private void givenMpPayment(String externalReference, Long id, String status) throws Exception {
        Payment payment = mock(Payment.class);
        lenient().when(payment.getId()).thenReturn(id);
        when(payment.getStatus()).thenReturn(status);
        when(mercadoPagoService.findLatestPaymentByExternalReference(externalReference)).thenReturn(Optional.of(payment));
    }

    private void givenCheckoutWorks() throws Exception {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        lenient().when(repository.save(any(ChallengePurchase.class))).thenAnswer(invocation -> {
            ChallengePurchase saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(77L);
            }
            return saved;
        });
        lenient().when(mercadoPagoService.createPreference(anyString(), any(BigDecimal.class), any(), anyString()))
                .thenReturn("https://mp.example/checkout");
        lenient().when(mercadoPagoService.createPreference(anyString(), any(BigDecimal.class), any(), anyString(), anyString()))
                .thenReturn("https://mp.example/checkout");
    }
}
