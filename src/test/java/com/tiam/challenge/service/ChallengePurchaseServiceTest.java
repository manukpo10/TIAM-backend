package com.tiam.challenge.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.dto.ChallengeAccessResponse;
import com.tiam.challenge.dto.CreatePurchaseRequest;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.ResourceNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChallengePurchaseServiceTest {

    private static final String ACCESS_TOKEN = "test-access-token";

    @Mock ChallengePurchaseRepository challengePurchaseRepository;
    @Mock MercadoPagoService mercadoPagoService;
    @Mock WhatsAppProperties whatsAppProperties;

    ChallengePurchaseService service;

    @BeforeEach
    void setUp() {
        service = new ChallengePurchaseService(challengePurchaseRepository, mercadoPagoService, whatsAppProperties);
    }

    @Test
    void getAccess_purchasedToday_returnsDay1() {
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now()));

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.currentDay()).isEqualTo(1);
    }

    @Test
    void getAccess_purchased4DaysAgo_returnsDay5() {
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.PAID,
            Instant.now().minus(4, ChronoUnit.DAYS)));

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.currentDay()).isEqualTo(5);
    }

    @Test
    void getAccess_purchased40DaysAgo_clampsTo30() {
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.PAID,
            Instant.now().minus(40, ChronoUnit.DAYS)));

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.currentDay()).isEqualTo(30);
    }

    @Test
    void getAccess_futurePurchaseDate_clampsTo1() {
        // +2 days guarantees a later AR calendar day regardless of the hour the
        // test happens to run at, so this can't flake around midnight.
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.PAID,
            Instant.now().plus(2, ChronoUnit.DAYS)));

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.currentDay()).isEqualTo(1);
    }

    @Test
    void getAccess_extractsFirstName() {
        givenPurchase(purchase("Manuel Alejandro Robles", ChallengePurchaseStatus.PAID, Instant.now()));

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.buyerFirstName()).isEqualTo("Manuel");
    }

    @Test
    void getAccess_lowercaseName_capitalizesFirstName() {
        givenPurchase(purchase("maria garcia", ChallengePurchaseStatus.PAID, Instant.now()));

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.buyerFirstName()).isEqualTo("Maria");
    }

    @Test
    void getAccess_totalDaysIsAlways30() {
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now()));

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.totalDays()).isEqualTo(30);
    }

    @Test
    void getAccess_month1Purchase_returnsChallengeMonth1() {
        // Purchase built via the fixture never sets challengeMonth explicitly, so it
        // relies on the entity's own default — proves getAccess surfaces that default
        // rather than hardcoding 1 itself.
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now()));

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.challengeMonth()).isEqualTo(1);
    }

    @Test
    void getAccess_month2Purchase_returnsChallengeMonth2() {
        // The frontend has no other way to know which catalog/game registry to
        // render for this access token before any day is completed — it must read
        // this field off the access response.
        ChallengePurchase month2Purchase = purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now());
        month2Purchase.setChallengeMonth(2);
        givenPurchase(month2Purchase);

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.challengeMonth()).isEqualTo(2);
    }

    @Test
    void getAccess_unknownToken_throwsNotFound() {
        when(challengePurchaseRepository.findByAccessTokenAndActivoTrue(ACCESS_TOKEN))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccess(ACCESS_TOKEN))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAccess_pendingStatus_throwsNotFound() {
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.PENDING, null));

        assertThatThrownBy(() -> service.getAccess(ACCESS_TOKEN))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAccess_failedStatus_throwsNotFound() {
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.FAILED, null));

        assertThatThrownBy(() -> service.getAccess(ACCESS_TOKEN))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAccess_paidButNullPurchaseDate_throwsNotFound() {
        // Isolates the null-purchaseDate guard from the status guard: a PAID row
        // whose purchaseDate was never set must 404, not NPE on the date math.
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.PAID, null));

        assertThatThrownBy(() -> service.getAccess(ACCESS_TOKEN))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createPurchase_normalizesPhoneBeforeSaving() throws MPException, MPApiException {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.save(any(ChallengePurchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mercadoPagoService.createPreference(any(), any(), any(), any()))
                .thenReturn("http://mock-init-point");

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", null);

        service.createPurchase(request);

        ArgumentCaptor<ChallengePurchase> captor = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(challengePurchaseRepository).save(captor.capture());
        assertThat(captor.getValue().getPhone()).isEqualTo("541122334455");
    }

    // --- createPurchase: challengeMonth --------------------------------------------

    @Test
    void createPurchase_noMonthSpecified_defaultsPurchaseToMonth1() throws MPException, MPApiException {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.save(any(ChallengePurchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mercadoPagoService.createPreference(any(), any(), any(), any()))
                .thenReturn("http://mock-init-point");

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", null);

        service.createPurchase(request);

        ArgumentCaptor<ChallengePurchase> captor = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(challengePurchaseRepository).save(captor.capture());
        assertThat(captor.getValue().getChallengeMonth()).isEqualTo(1);
    }

    @Test
    void createPurchase_noMonthSpecified_usesBaseItemTitleWithNoMonthSuffix()
            throws MPException, MPApiException {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.save(any(ChallengePurchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mercadoPagoService.createPreference(any(), any(), any(), any()))
                .thenReturn("http://mock-init-point");

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", null);

        service.createPurchase(request);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(mercadoPagoService).createPreference(titleCaptor.capture(), any(), any(), any());
        assertThat(titleCaptor.getValue()).isEqualTo("Desafío 30 días - TIAM Digital");
    }

    @Test
    void createPurchase_month2_storesMonthOnThePurchase() throws MPException, MPApiException {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.save(any(ChallengePurchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mercadoPagoService.createPreference(any(), any(), any(), any()))
                .thenReturn("http://mock-init-point");

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", 2);

        service.createPurchase(request);

        ArgumentCaptor<ChallengePurchase> captor = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(challengePurchaseRepository).save(captor.capture());
        assertThat(captor.getValue().getChallengeMonth()).isEqualTo(2);
    }

    @Test
    void createPurchase_month2_appendsMonthToItemTitle() throws MPException, MPApiException {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.save(any(ChallengePurchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mercadoPagoService.createPreference(any(), any(), any(), any()))
                .thenReturn("http://mock-init-point");

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", 2);

        service.createPurchase(request);

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(mercadoPagoService).createPreference(titleCaptor.capture(), any(), any(), any());
        assertThat(titleCaptor.getValue()).isEqualTo("Desafío 30 días - TIAM Digital - Mes 2");
    }

    @Test
    void createPurchase_explicitMonth1_isAccepted() throws MPException, MPApiException {
        // 1 sent explicitly (not null) must be treated the same as "absent" — it's
        // in the allowlist, not just the implicit default.
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.save(any(ChallengePurchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mercadoPagoService.createPreference(any(), any(), any(), any()))
                .thenReturn("http://mock-init-point");

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", 1);

        service.createPurchase(request);

        verify(challengePurchaseRepository).save(any(ChallengePurchase.class));
    }

    @Test
    void createPurchase_unsupportedMonthAboveTwo_throwsBadRequestAndPersistsNothing() {
        // Validation must short-circuit before isConfigured()/persistence/MP — no
        // stub for mercadoPagoService.isConfigured() here on purpose: reaching it
        // for real (not as a stub setup) would fail verifyNoInteractions below.
        // Asserting on the message (not just the exception type) matters here:
        // the pre-existing "MP not configured" guard also throws a bare
        // BadRequestException, and with isConfigured() unstubbed (defaults to
        // false) that guard would produce a false-green for the wrong reason if
        // this test only checked isInstanceOf.
        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", 3);

        assertThatThrownBy(() -> service.createPurchase(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("challenge month");

        verifyNoInteractions(challengePurchaseRepository, mercadoPagoService);
    }

    @Test
    void createPurchase_unsupportedMonthZero_throwsBadRequest() {
        // Guards that the "positive but not in {1,2}" allowlist doesn't
        // accidentally accept non-positive values too (an off-by-one a ">2"
        // check alone would miss).
        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", 0);

        assertThatThrownBy(() -> service.createPurchase(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("challenge month");

        verifyNoInteractions(challengePurchaseRepository, mercadoPagoService);
    }

    @Test
    void findActiveByPhone_singlePaidMatch_returnsIt() {
        ChallengePurchase purchase = purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now());
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(purchase));

        Optional<ChallengePurchase> result = service.findActiveByPhone("541122334455");

        assertThat(result).contains(purchase);
    }

    @Test
    void findActiveByPhone_multiplePaidMatches_returnsMostRecentByPurchaseDate() {
        ChallengePurchase older = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(10, ChronoUnit.DAYS));
        ChallengePurchase newer = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(2, ChronoUnit.DAYS));
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(older, newer));

        Optional<ChallengePurchase> result = service.findActiveByPhone("541122334455");

        assertThat(result).contains(newer);
    }

    @Test
    void findActiveByPhone_paidMatchesAcrossDifferentMonths_returnsMostRecentByPurchaseDate() {
        // Buying month 2 is a brand-new purchase row, always dated later than an
        // older month-1 purchase for the same phone. findActiveByPhone's existing
        // "most recent by purchaseDate" logic must already prefer it — this proves
        // that holds without needing any month-aware change to the method itself.
        ChallengePurchase month1Older = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(10, ChronoUnit.DAYS));
        month1Older.setChallengeMonth(1);
        ChallengePurchase month2Newer = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(1, ChronoUnit.DAYS));
        month2Newer.setChallengeMonth(2);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(month1Older, month2Newer));

        Optional<ChallengePurchase> result = service.findActiveByPhone("541122334455");

        assertThat(result).contains(month2Newer);
        assertThat(result.get().getChallengeMonth()).isEqualTo(2);
    }

    @Test
    void findActiveByPhone_onlyPendingMatch_returnsEmpty() {
        ChallengePurchase pending = purchase("Ana Diaz", ChallengePurchaseStatus.PENDING, null);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(pending));

        Optional<ChallengePurchase> result = service.findActiveByPhone("541122334455");

        assertThat(result).isEmpty();
    }

    @Test
    void findActiveByPhone_noMatch_returnsEmpty() {
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of());

        Optional<ChallengePurchase> result = service.findActiveByPhone("541122334455");

        assertThat(result).isEmpty();
    }

    @Test
    void buildWhatsAppReply_matchedPhone_includesFirstNameDayAndLink() {
        ChallengePurchase purchase = purchase("Manuel Robles", ChallengePurchaseStatus.PAID,
                Instant.now().minus(4, ChronoUnit.DAYS));
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(purchase));
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn("http://localhost:5173/desafio");

        String reply = service.buildWhatsAppReply("541122334455");

        assertThat(reply)
                .contains("Manuel")
                .contains("Día")
                .contains("http://localhost:5173/desafio/test-access-token");
    }

    @Test
    void buildWhatsAppReply_unmatchedPhone_includesSalesPageUrl() {
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of());
        when(whatsAppProperties.getSalesPageUrl()).thenReturn("http://localhost:5173/desafio-30-dias");

        String reply = service.buildWhatsAppReply("541122334455");

        assertThat(reply).contains("http://localhost:5173/desafio-30-dias");
    }

    @Test
    void buildWhatsAppReply_matchedDayUnderThirty_includesTomorrowLine() {
        ChallengePurchase purchase = purchase("Manuel Robles", ChallengePurchaseStatus.PAID,
                Instant.now().minus(4, ChronoUnit.DAYS));
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(purchase));
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn("http://localhost:5173/desafio");

        String reply = service.buildWhatsAppReply("541122334455");

        assertThat(reply)
                .contains("Día 5")
                .contains("http://localhost:5173/desafio/test-access-token")
                .contains("Día 6")
                .contains("desafío");
    }

    @Test
    void buildWhatsAppReply_matchedDayThirty_includesCompletion() {
        ChallengePurchase purchase = purchase("Manuel Robles", ChallengePurchaseStatus.PAID,
                Instant.now().minus(40, ChronoUnit.DAYS));
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(purchase));
        when(whatsAppProperties.getDesafioPlayBaseUrl()).thenReturn("http://localhost:5173/desafio");

        String reply = service.buildWhatsAppReply("541122334455");

        assertThat(reply)
                .contains("último")
                .contains("Completaste")
                .doesNotContain("Día 31");
    }

    @Test
    void buildWhatsAppReply_pendingPurchaseNoPaid_returnsConfirmingMessage() {
        ChallengePurchase pending = purchase("Ana Diaz", ChallengePurchaseStatus.PENDING, null);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(pending));

        String reply = service.buildWhatsAppReply("541122334455");

        assertThat(reply).contains("Estamos confirmando tu pago").contains("desafío");
    }

    @Test
    void buildWhatsAppReply_noPurchase_returnsSalesFallback() {
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of());
        when(whatsAppProperties.getSalesPageUrl()).thenReturn("http://localhost:5173/desafio-30-dias");

        String reply = service.buildWhatsAppReply("541122334455");

        assertThat(reply).contains("http://localhost:5173/desafio-30-dias");
    }

    // --- fixtures -------------------------------------------------------------

    private void givenPurchase(ChallengePurchase purchase) {
        when(challengePurchaseRepository.findByAccessTokenAndActivoTrue(ACCESS_TOKEN))
            .thenReturn(Optional.of(purchase));
    }

    private ChallengePurchase purchase(String buyerName, ChallengePurchaseStatus status, Instant purchaseDate) {
        ChallengePurchase p = new ChallengePurchase();
        p.setBuyerName(buyerName);
        p.setPhone("+5491100000000");
        p.setEmail("buyer@example.com");
        p.setStatus(status);
        p.setAccessToken(ACCESS_TOKEN);
        p.setPurchaseDate(purchaseDate);
        return p;
    }
}
