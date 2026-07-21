package com.tiam.challenge.service;

import com.tiam.challenge.domain.ChallengeDayResult;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.dto.ChallengeBadgeResponse;
import com.tiam.challenge.dto.ChallengeDayResultResponse;
import com.tiam.challenge.dto.ChallengeProgressResponse;
import com.tiam.challenge.dto.CompleteDayRequest;
import com.tiam.challenge.repository.ChallengeDayResultRepository;
import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChallengeDayResultServiceTest {

    private static final String ACCESS_TOKEN = "test-access-token";
    private static final long PURCHASE_ID = 42L;

    @Mock ChallengeDayResultRepository challengeDayResultRepository;
    @Mock ChallengePurchaseService challengePurchaseService;

    ChallengeDayResultService service;

    @BeforeEach
    void setUp() {
        service = new ChallengeDayResultService(challengeDayResultRepository, challengePurchaseService);
    }

    // --- completeDay: star tiers ------------------------------------------------

    @Test
    void completeDay_perfectAccuracy_awardsThreeStars() {
        givenPurchase(1);
        givenNoExistingResult(1);

        ChallengeDayResultResponse response = service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(0, 10));

        assertThat(response.stars()).isEqualTo(3);
    }

    @Test
    void completeDay_accuracyExactlyAtThreeStarThreshold_awardsThreeStars() {
        // 17/20 = 0.85 — exactly the >= 0.85 boundary for 3 stars.
        givenPurchase(1);
        givenNoExistingResult(1);

        ChallengeDayResultResponse response = service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(3, 20));

        assertThat(response.stars()).isEqualTo(3);
    }

    @Test
    void completeDay_accuracyJustBelowThreeStarThreshold_awardsTwoStars() {
        // 16/20 = 0.80 — just under the 0.85 boundary.
        givenPurchase(1);
        givenNoExistingResult(1);

        ChallengeDayResultResponse response = service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(4, 20));

        assertThat(response.stars()).isEqualTo(2);
    }

    @Test
    void completeDay_accuracyExactlyAtTwoStarThreshold_awardsTwoStars() {
        // 6/10 = 0.6 — exactly the >= 0.6 boundary for 2 stars.
        givenPurchase(1);
        givenNoExistingResult(1);

        ChallengeDayResultResponse response = service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(4, 10));

        assertThat(response.stars()).isEqualTo(2);
    }

    @Test
    void completeDay_lowAccuracy_neverDropsBelowOneStar() {
        // 0/4 correct — still floors at 1 star, never 0.
        givenPurchase(1);
        givenNoExistingResult(1);

        ChallengeDayResultResponse response = service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(4, 4));

        assertThat(response.stars()).isEqualTo(1);
    }

    @Test
    void completeDay_paperAndPencilDay_zeroAttempts_awardsThreeStarsForParticipation() {
        // Papel-y-lápiz days (6, 12, 18, 30) report (mistakes:0, totalAttempts:0).
        // 0 attempts is treated as full accuracy so the day counts (3 stars por
        // participar) and keeps the streak alive, without dividing by zero. Day 6 is
        // a GAME day in the catalog.
        givenPurchase(6);
        givenNoExistingResult(6);

        ChallengeDayResultResponse response = service.completeDay(ACCESS_TOKEN, 6, new CompleteDayRequest(0, 0));

        assertThat(response.stars()).isEqualTo(3);
        assertThat(response.totalAttempts()).isZero();
    }

    // --- completeDay: validation -------------------------------------------------

    @Test
    void completeDay_dayBeyondCurrentDay_throwsBadRequest() {
        givenPurchase(1); // currentDay = 1, day 2 not unlocked yet

        assertThatThrownBy(() -> service.completeDay(ACCESS_TOKEN, 2, new CompleteDayRequest(0, 5)))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void completeDay_dayZero_throwsBadRequest() {
        givenPurchase(5);

        assertThatThrownBy(() -> service.completeDay(ACCESS_TOKEN, 0, new CompleteDayRequest(0, 5)))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void completeDay_mistakesExceedTotalAttempts_throwsBadRequest() {
        givenPurchase(5);

        assertThatThrownBy(() -> service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(6, 5)))
            .isInstanceOf(BadRequestException.class);
    }

    @Test
    void completeDay_unknownOrUnpaidToken_throwsNotFound() {
        when(challengePurchaseService.resolvePaidPurchase(ACCESS_TOKEN))
            .thenThrow(new ResourceNotFoundException("Challenge access not found: " + ACCESS_TOKEN));

        assertThatThrownBy(() -> service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(0, 5)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- completeDay: upsert / keep-best ------------------------------------------

    @Test
    void completeDay_noExistingResult_insertsNewRowWithDerivedArea() {
        givenPurchase(1);
        givenNoExistingResult(1);

        service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(0, 10));

        ArgumentCaptor<ChallengeDayResult> captor = ArgumentCaptor.forClass(ChallengeDayResult.class);
        verify(challengeDayResultRepository).save(captor.capture());
        assertThat(captor.getValue().getStars()).isEqualTo(3);
        assertThat(captor.getValue().getArea()).isEqualTo("lenguaje"); // day 1's catalog area
        assertThat(captor.getValue().getPlayedAt()).isNotNull();
    }

    @Test
    void completeDay_replayWithHigherStars_overwritesExisting() {
        givenPurchase(1);
        ChallengeDayResult existing = existingResult(1, 1); // currently 1 star
        when(challengeDayResultRepository.findByChallengePurchaseIdAndDayAndActivoTrue(PURCHASE_ID, 1))
            .thenReturn(Optional.of(existing));
        when(challengeDayResultRepository.save(any(ChallengeDayResult.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ChallengeDayResultResponse response =
            service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(0, 10)); // -> 3 stars

        assertThat(response.stars()).isEqualTo(3);
        verify(challengeDayResultRepository).save(existing);
        assertThat(existing.getStars()).isEqualTo(3);
    }

    @Test
    void completeDay_replayWithLowerStars_keepsExistingBestAndDoesNotSave() {
        givenPurchase(1);
        ChallengeDayResult existing = existingResult(1, 3); // already 3 stars
        when(challengeDayResultRepository.findByChallengePurchaseIdAndDayAndActivoTrue(PURCHASE_ID, 1))
            .thenReturn(Optional.of(existing));

        ChallengeDayResultResponse response =
            service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(4, 4)); // -> 1 star

        assertThat(response.stars()).isEqualTo(3); // reports the kept best, not the discarded replay
        verify(challengeDayResultRepository, never()).save(any());
    }

    @Test
    void completeDay_replayWithEqualStars_stillOverwritesMistakes() {
        givenPurchase(1);
        ChallengeDayResult existing = existingResult(1, 2);
        existing.setMistakes(4);
        existing.setTotalAttempts(10);
        when(challengeDayResultRepository.findByChallengePurchaseIdAndDayAndActivoTrue(PURCHASE_ID, 1))
            .thenReturn(Optional.of(existing));
        when(challengeDayResultRepository.save(any(ChallengeDayResult.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // 2/10 mistakes -> 0.8 accuracy -> still 2 stars, but a different mistake count.
        service.completeDay(ACCESS_TOKEN, 1, new CompleteDayRequest(2, 10));

        verify(challengeDayResultRepository).save(existing);
        assertThat(existing.getMistakes()).isEqualTo(2);
    }

    // --- getProgress: streak --------------------------------------------------------

    @Test
    void getProgress_missingGameDay_breaksStreak() {
        // Purchase 6 days ago -> currentDay = 7, day 4 has no result.
        Instant purchaseDate = Instant.now().minusSeconds(6 * 86_400L);
        givenPurchase(purchaseDate, 7);
        givenResults(List.of(
            resultForDay(1), resultForDay(2), resultForDay(3),
            resultForDay(5), resultForDay(6), resultForDay(7)
        )); // day 4 missing breaks the chain

        ChallengeProgressResponse progress = service.getProgress(ACCESS_TOKEN);

        assertThat(progress.streak().current()).isEqualTo(3); // day5 -> day6 -> day7
        assertThat(progress.streak().longest()).isEqualTo(3); // ties the day1-3 run
    }

    // --- getProgress: badges ---------------------------------------------------------

    @Test
    void getProgress_noResults_noBadgesEarned() {
        givenPurchase(Instant.now(), 1);
        givenResults(List.of());

        ChallengeProgressResponse progress = service.getProgress(ACCESS_TOKEN);

        assertThat(progress.badges()).allSatisfy(b -> assertThat(b.earned()).isFalse());
    }

    @Test
    void getProgress_anyThreeStarDay_earnsPerfectDayAndFirstDayBadges() {
        givenPurchase(Instant.now(), 1);
        ChallengeDayResult perfect = resultForDay(1);
        perfect.setStars(3);
        givenResults(List.of(perfect));

        ChallengeProgressResponse progress = service.getProgress(ACCESS_TOKEN);

        assertThat(badge(progress, "PERFECT_DAY").earned()).isTrue();
        assertThat(badge(progress, "FIRST_DAY").earned()).isTrue();
        assertThat(badge(progress, "HALFWAY").earned()).isFalse();
    }

    // --- getProgress: area breakdown --------------------------------------------------

    @Test
    void getProgress_noResults_includesAllEightAreasWithZeroPlayed() {
        givenPurchase(Instant.now(), 1);
        givenResults(List.of());

        ChallengeProgressResponse progress = service.getProgress(ACCESS_TOKEN);

        assertThat(progress.areaBreakdown()).hasSize(8);
        assertThat(progress.areaBreakdown()).allSatisfy(a -> {
            assertThat(a.played()).isZero();
            assertThat(a.averageStars()).isZero();
        });
    }

    @Test
    void getProgress_unknownOrUnpaidToken_throwsNotFound() {
        when(challengePurchaseService.resolvePaidPurchase(ACCESS_TOKEN))
            .thenThrow(new ResourceNotFoundException("Challenge access not found: " + ACCESS_TOKEN));

        assertThatThrownBy(() -> service.getProgress(ACCESS_TOKEN))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- fixtures ---------------------------------------------------------------------

    private void givenPurchase(int currentDay) {
        givenPurchase(Instant.now(), currentDay);
    }

    private void givenPurchase(Instant purchaseDate, int currentDay) {
        ChallengePurchase purchase = new ChallengePurchase();
        purchase.setId(PURCHASE_ID);
        purchase.setStatus(ChallengePurchaseStatus.PAID);
        purchase.setAccessToken(ACCESS_TOKEN);
        purchase.setPurchaseDate(purchaseDate);
        when(challengePurchaseService.resolvePaidPurchase(ACCESS_TOKEN)).thenReturn(purchase);
        when(challengePurchaseService.computeCurrentDay(purchaseDate)).thenReturn(currentDay);
    }

    private void givenNoExistingResult(int day) {
        when(challengeDayResultRepository.findByChallengePurchaseIdAndDayAndActivoTrue(PURCHASE_ID, day))
            .thenReturn(Optional.empty());
        // completeDay will insert via save() in this scenario — echo the argument
        // back so toResponse(...) has a non-null, fully-populated result to map.
        when(challengeDayResultRepository.save(any(ChallengeDayResult.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void givenResults(List<ChallengeDayResult> results) {
        when(challengeDayResultRepository.findByChallengePurchaseIdAndActivoTrueOrderByDayAsc(PURCHASE_ID))
            .thenReturn(results);
    }

    private ChallengeDayResult existingResult(int day, int stars) {
        ChallengeDayResult result = resultForDay(day);
        result.setStars(stars);
        return result;
    }

    private ChallengeDayResult resultForDay(int day) {
        ChallengeDayResult result = new ChallengeDayResult();
        result.setDay(day);
        result.setArea("memoria");
        result.setMistakes(0);
        result.setTotalAttempts(1);
        result.setStars(2);
        result.setPlayedAt(Instant.now());
        return result;
    }

    private ChallengeBadgeResponse badge(ChallengeProgressResponse progress, String code) {
        return progress.badges().stream()
            .filter(b -> b.code().equals(code))
            .findFirst()
            .orElseThrow();
    }
}
