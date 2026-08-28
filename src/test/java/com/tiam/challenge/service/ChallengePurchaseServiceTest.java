package com.tiam.challenge.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.dto.ChallengeAccessResponse;
import com.tiam.challenge.dto.CreatePurchaseRequest;
import com.tiam.challenge.dto.CreatePurchaseResponse;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
    void createPurchase_unsupportedMonthAboveThree_throwsBadRequestAndPersistsNothing() {
        // Validation must short-circuit before isConfigured()/persistence/MP — no
        // stub for mercadoPagoService.isConfigured() here on purpose: reaching it
        // for real (not as a stub setup) would fail verifyNoInteractions below.
        // Asserting on the message (not just the exception type) matters here:
        // the pre-existing "MP not configured" guard also throws a bare
        // BadRequestException, and with isConfigured() unstubbed (defaults to
        // false) that guard would produce a false-green for the wrong reason if
        // this test only checked isInstanceOf. Uses 4, not 3 — month 3 is a real,
        // supported catalog now (see the createPurchase_month3* tests below).
        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", 4);

        assertThatThrownBy(() -> service.createPurchase(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("challenge month");

        verifyNoInteractions(challengePurchaseRepository, mercadoPagoService);
    }

    @Test
    void createPurchase_explicitMonth3_isAccepted() throws MPException, MPApiException {
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.save(any(ChallengePurchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mercadoPagoService.createPreference(any(), any(), any(), any()))
                .thenReturn("http://mock-init-point");

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", 3);

        service.createPurchase(request);

        ArgumentCaptor<ChallengePurchase> captor = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(challengePurchaseRepository).save(captor.capture());
        assertThat(captor.getValue().getChallengeMonth()).isEqualTo(3);
    }

    // --- createPurchase: auto-assigning the next unpaid month ----------------------

    @Test
    void createPurchase_firstTimePhone_autoAssignsMonth1() throws MPException, MPApiException {
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of());
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
    void createPurchase_phoneAlreadyPaidMonth1_autoAssignsMonth2() throws MPException, MPApiException {
        ChallengePurchase month1 = purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now());
        month1.setChallengeMonth(1);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(month1));
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
        assertThat(captor.getValue().getChallengeMonth()).isEqualTo(2);
    }

    @Test
    void createPurchase_phoneHasOnlyPendingMonth1_stillAutoAssignsMonth1() throws MPException, MPApiException {
        // An abandoned/unfinished checkout for month 1 must NOT count as "already
        // has month 1" — otherwise a buyer retrying a failed payment would get
        // silently bumped to month 2 without ever actually owning month 1.
        ChallengePurchase pendingMonth1 =
                purchase("Manuel Robles", ChallengePurchaseStatus.PENDING, null);
        pendingMonth1.setChallengeMonth(1);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(pendingMonth1));
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
    void createPurchase_phoneAlreadyPaidMonths1And2_autoAssignsMonth3() throws MPException, MPApiException {
        ChallengePurchase month1 = purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now());
        month1.setChallengeMonth(1);
        ChallengePurchase month2 = purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now());
        month2.setChallengeMonth(2);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(month1, month2));
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
        assertThat(captor.getValue().getChallengeMonth()).isEqualTo(3);
    }

    @Test
    void createPurchase_phoneAlreadyPaidAllThreeMonths_throwsBadRequestAndPersistsNothing() {
        ChallengePurchase month1 = purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now());
        month1.setChallengeMonth(1);
        ChallengePurchase month2 = purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now());
        month2.setChallengeMonth(2);
        ChallengePurchase month3 = purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now());
        month3.setChallengeMonth(3);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(month1, month2, month3));

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", null);

        assertThatThrownBy(() -> service.createPurchase(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("3 meses");

        verifyNoInteractions(mercadoPagoService);
        verify(challengePurchaseRepository, never()).save(any());
    }

    @Test
    void createPurchase_concurrentRequestsFromSamePhone_neverInterleaveTheCriticalSection()
            throws Exception {
        // Proves the per-phone lock actually serializes concurrent same-phone
        // requests — they never both execute the compute-then-save section at
        // once. Does NOT prove they end up with different months (they may
        // not — see the "what this does NOT close" note on createPurchase).
        AtomicInteger concurrentEntries = new AtomicInteger();
        AtomicInteger maxConcurrentEntries = new AtomicInteger();
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of());
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.save(any(ChallengePurchase.class)))
                .thenAnswer(invocation -> {
                    int concurrent = concurrentEntries.incrementAndGet();
                    maxConcurrentEntries.updateAndGet(max -> Math.max(max, concurrent));
                    Thread.sleep(50); // widen the window so a real race would actually overlap
                    concurrentEntries.decrementAndGet();
                    return invocation.getArgument(0);
                });
        when(mercadoPagoService.createPreference(any(), any(), any(), any()))
                .thenReturn("http://mock-init-point");

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<CreatePurchaseResponse>> futures = pool.invokeAll(
                    List.of(() -> service.createPurchase(request), () -> service.createPurchase(request)));
            for (Future<CreatePurchaseResponse> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        assertThat(maxConcurrentEntries.get()).isEqualTo(1);
    }

    @Test
    void createPurchase_explicitMonthRequested_skipsAutoAssignEvenIfPhoneHasNoHistory()
            throws MPException, MPApiException {
        // An explicit challengeMonth must win outright — no phone lookup at all —
        // so a future caller that legitimately wants to grant a specific month
        // (e.g. manual support intervention) isn't second-guessed by history.
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(challengePurchaseRepository.save(any(ChallengePurchase.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(mercadoPagoService.createPreference(any(), any(), any(), any()))
                .thenReturn("http://mock-init-point");

        CreatePurchaseRequest request =
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com", 2);

        service.createPurchase(request);

        verify(challengePurchaseRepository, never()).findByPhoneAndActivoTrue(any());
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
    void findActiveByPhone_earlierMonthStillInProgress_prefersItOverNewerMonth() {
        // Bought month 2 while still mid-way through month 1: the WhatsApp
        // "desafío" bot must keep answering about month 1 (the unfinished
        // one) rather than jumping to the buyer's most recent purchase —
        // otherwise there'd be no in-band way to ever finish month 1.
        ChallengePurchase month1InProgress = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(10, ChronoUnit.DAYS));
        month1InProgress.setChallengeMonth(1);
        ChallengePurchase month2Newer = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(1, ChronoUnit.DAYS));
        month2Newer.setChallengeMonth(2);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(month1InProgress, month2Newer));

        Optional<ChallengePurchase> result = service.findActiveByPhone("541122334455");

        assertThat(result).contains(month1InProgress);
        assertThat(result.get().getChallengeMonth()).isEqualTo(1);
    }

    @Test
    void findActiveByPhone_earlierMonthAlreadyFinished_movesOnToNewerMonth() {
        // Once month 1 is actually done (day 30+), the bot should move on to
        // the next PAID month that's still in progress.
        ChallengePurchase month1Finished = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(40, ChronoUnit.DAYS));
        month1Finished.setChallengeMonth(1);
        ChallengePurchase month2InProgress = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(5, ChronoUnit.DAYS));
        month2InProgress.setChallengeMonth(2);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(month1Finished, month2InProgress));

        Optional<ChallengePurchase> result = service.findActiveByPhone("541122334455");

        assertThat(result).contains(month2InProgress);
        assertThat(result.get().getChallengeMonth()).isEqualTo(2);
    }

    @Test
    void findActiveByPhone_allPaidMonthsFinished_fallsBackToMostRecentlyPurchased() {
        // No purchase is "in progress" anymore — falls back to the most
        // recently bought one so a buyer who finished everything still gets
        // a sensible completion message instead of an arbitrary earlier one.
        ChallengePurchase month1Finished = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(40, ChronoUnit.DAYS));
        month1Finished.setChallengeMonth(1);
        ChallengePurchase month2FinishedMoreRecently = purchase("Ana Diaz", ChallengePurchaseStatus.PAID,
                Instant.now().minus(31, ChronoUnit.DAYS));
        month2FinishedMoreRecently.setChallengeMonth(2);
        when(challengePurchaseRepository.findByPhoneAndActivoTrue("541122334455"))
                .thenReturn(List.of(month1Finished, month2FinishedMoreRecently));

        Optional<ChallengePurchase> result = service.findActiveByPhone("541122334455");

        assertThat(result).contains(month2FinishedMoreRecently);
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
