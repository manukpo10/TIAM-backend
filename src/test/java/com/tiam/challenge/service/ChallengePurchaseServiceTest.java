package com.tiam.challenge.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.dto.ChallengeAccessResponse;
import com.tiam.challenge.dto.CreatePurchaseRequest;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
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
    void getAccess_totalDaysIsAlways30() {
        givenPurchase(purchase("Manuel Robles", ChallengePurchaseStatus.PAID, Instant.now()));

        ChallengeAccessResponse response = service.getAccess(ACCESS_TOKEN);

        assertThat(response.totalDays()).isEqualTo(30);
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
                new CreatePurchaseRequest("Manuel Robles", "11 2233-4455", "buyer@example.com");

        service.createPurchase(request);

        ArgumentCaptor<ChallengePurchase> captor = ArgumentCaptor.forClass(ChallengePurchase.class);
        verify(challengePurchaseRepository).save(captor.capture());
        assertThat(captor.getValue().getPhone()).isEqualTo("541122334455");
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
