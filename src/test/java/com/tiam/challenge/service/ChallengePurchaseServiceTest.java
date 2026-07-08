package com.tiam.challenge.service;

import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.dto.ChallengeAccessResponse;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.subscription.service.MercadoPagoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChallengePurchaseServiceTest {

    private static final String ACCESS_TOKEN = "test-access-token";

    @Mock ChallengePurchaseRepository challengePurchaseRepository;
    @Mock MercadoPagoService mercadoPagoService;

    ChallengePurchaseService service;

    @BeforeEach
    void setUp() {
        service = new ChallengePurchaseService(challengePurchaseRepository, mercadoPagoService);
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
