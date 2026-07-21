package com.tiam.challenge.service;

import com.tiam.challenge.domain.ChallengeDayCatalog;
import com.tiam.challenge.domain.ChallengeDayResult;
import com.tiam.challenge.domain.ChallengeDayType;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.dto.ChallengeAreaBreakdownResponse;
import com.tiam.challenge.dto.ChallengeBadgeResponse;
import com.tiam.challenge.dto.ChallengeDayResultResponse;
import com.tiam.challenge.dto.ChallengeProgressResponse;
import com.tiam.challenge.dto.ChallengeStreakResponse;
import com.tiam.challenge.dto.CompleteDayRequest;
import com.tiam.challenge.repository.ChallengeDayResultRepository;
import com.tiam.common.exception.BadRequestException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChallengeDayResultService {

    // Two games already use an independent >=60% cutoff for their own "bien"/"ok"
    // feedback — reused here for the 2-star cutoff so it matches what the player
    // already saw in-game. 3-star is generous (not 100%) so one slip doesn't cost it.
    private static final double THREE_STAR_ACCURACY = 0.85;
    private static final double TWO_STAR_ACCURACY = 0.6;

    // "Halfway" is proportional to the *playable* (game-type) day count, not to 30 —
    // derived from the catalog so it can't drift if that count ever changes.
    private static final long HALFWAY_GAME_DAYS = (long) Math.ceil(ChallengeDayCatalog.GAME_DAY_COUNT / 2.0);
    private static final int STREAK_BADGE_SHORT = 3;
    private static final int STREAK_BADGE_LONG = 7;

    private final ChallengeDayResultRepository challengeDayResultRepository;
    private final ChallengePurchaseService challengePurchaseService;

    /**
     * Records (or upserts) a day's result. {@code area} is always derived from
     * {@link ChallengeDayCatalog}, never from the request. {@code playedAt} is
     * always {@code Instant.now()}, never client-supplied. A replay only
     * overwrites the existing row if it scores at least as well (keep-best) —
     * replaying can never lower a star rating you already earned.
     */
    @Transactional
    public ChallengeDayResultResponse completeDay(String accessToken, int day, CompleteDayRequest request) {
        ChallengePurchase purchase = challengePurchaseService.resolvePaidPurchase(accessToken);
        int currentDay = challengePurchaseService.computeCurrentDay(purchase.getPurchaseDate());

        if (day < 1 || day > currentDay) {
            throw new BadRequestException("Day " + day + " is not unlocked yet");
        }
        if (request.mistakes() > request.totalAttempts()) {
            throw new BadRequestException("mistakes cannot exceed totalAttempts");
        }

        // day is within [1, currentDay] and currentDay is already clamped to
        // [1, 30] by computeCurrentDay, so this is always a valid catalog key.
        ChallengeDayCatalog.DayInfo dayInfo = ChallengeDayCatalog.dayInfo(day);
        if (dayInfo.type() != ChallengeDayType.GAME) {
            // 'card' days are static reflection content with no completion event —
            // there's nothing to grade, so recording a result for one is rejected
            // rather than silently accepted as bogus data.
            throw new BadRequestException("Day " + day + " has no completable game");
        }

        int mistakes = request.mistakes();
        int totalAttempts = request.totalAttempts();
        double accuracy = totalAttempts == 0 ? 1.0 : (totalAttempts - mistakes) / (double) totalAttempts;
        int stars = accuracy >= THREE_STAR_ACCURACY ? 3 : accuracy >= TWO_STAR_ACCURACY ? 2 : 1;

        ChallengeDayResult result = challengeDayResultRepository
                .findByChallengePurchaseIdAndDayAndActivoTrue(purchase.getId(), day)
                .orElse(null);

        if (result == null) {
            result = new ChallengeDayResult();
            result.setChallengePurchase(purchase);
            result.setDay(day);
            result.setArea(dayInfo.area());
            result.setMistakes(mistakes);
            result.setTotalAttempts(totalAttempts);
            result.setStars(stars);
            result.setPlayedAt(Instant.now());
            result = challengeDayResultRepository.save(result);
        } else if (stars >= result.getStars()) {
            // Re-derive area too (not just mistakes/stars) so an update never
            // trusts a stale value on the existing row, even though it can't
            // actually differ in practice since the catalog mapping is static.
            result.setArea(dayInfo.area());
            result.setMistakes(mistakes);
            result.setTotalAttempts(totalAttempts);
            result.setStars(stars);
            result.setPlayedAt(Instant.now());
            result = challengeDayResultRepository.save(result);
        }
        // else: this replay scored worse than the existing best — keep the
        // existing row untouched and report it back as-is.

        return toResponse(result);
    }

    /**
     * Builds the full progress payload: per-day results, streak, badges and
     * the per-area breakdown (all 7 areas, including zero-played ones).
     */
    @Transactional(readOnly = true)
    public ChallengeProgressResponse getProgress(String accessToken) {
        ChallengePurchase purchase = challengePurchaseService.resolvePaidPurchase(accessToken);
        int currentDay = challengePurchaseService.computeCurrentDay(purchase.getPurchaseDate());

        List<ChallengeDayResult> results = challengeDayResultRepository
                .findByChallengePurchaseIdAndActivoTrueOrderByDayAsc(purchase.getId());
        Map<Integer, ChallengeDayResult> resultsByDay = results.stream()
                .collect(Collectors.toMap(ChallengeDayResult::getDay, r -> r));

        ChallengeStreakResponse streak = computeStreak(resultsByDay, currentDay);
        List<ChallengeBadgeResponse> badges = computeBadges(results, streak);
        List<ChallengeAreaBreakdownResponse> areaBreakdown = computeAreaBreakdown(results);
        List<ChallengeDayResultResponse> days = results.stream().map(this::toResponse).toList();

        return new ChallengeProgressResponse(days, streak, badges, areaBreakdown);
    }

    /**
     * Walks the catalog from day 1 up to {@code currentDay} — days beyond that
     * haven't unlocked yet, so they can neither extend nor break the chain (an
     * unplayed 'card' day past currentDay would otherwise show as a false
     * "pass-through"). 'card' days are automatic pass-throughs that never break
     * the chain; a 'game' day only keeps it alive if it has a recorded result.
     * "current" is the run ending exactly at currentDay: if today's game hasn't
     * been played yet, current reads 0 until it is.
     */
    private ChallengeStreakResponse computeStreak(Map<Integer, ChallengeDayResult> resultsByDay, int currentDay) {
        int running = 0;
        int longest = 0;
        for (int day = 1; day <= currentDay; day++) {
            ChallengeDayCatalog.DayInfo dayInfo = ChallengeDayCatalog.dayInfo(day);
            boolean alive = dayInfo.type() != ChallengeDayType.GAME || resultsByDay.containsKey(day);
            running = alive ? running + 1 : 0;
            longest = Math.max(longest, running);
        }
        return new ChallengeStreakResponse(running, longest);
    }

    /**
     * Fixed, non-exhaustive badge set. Streak badges use the longest streak
     * ever reached (not the current one) so they stay earned even after a
     * later missed day resets the current streak — badges are permanent.
     */
    private List<ChallengeBadgeResponse> computeBadges(
            List<ChallengeDayResult> results, ChallengeStreakResponse streak) {
        // completeDay rejects 'card' days, so every persisted result is a game day.
        int gameDaysPlayed = results.size();
        boolean anyThreeStars = results.stream().anyMatch(r -> r.getStars() == 3);

        List<ChallengeBadgeResponse> badges = new ArrayList<>();
        badges.add(new ChallengeBadgeResponse("FIRST_DAY", !results.isEmpty()));
        badges.add(new ChallengeBadgeResponse("STREAK_3", streak.longest() >= STREAK_BADGE_SHORT));
        badges.add(new ChallengeBadgeResponse("STREAK_7", streak.longest() >= STREAK_BADGE_LONG));
        badges.add(new ChallengeBadgeResponse("HALFWAY", gameDaysPlayed >= HALFWAY_GAME_DAYS));
        badges.add(new ChallengeBadgeResponse(
                "CHALLENGE_COMPLETE", gameDaysPlayed >= ChallengeDayCatalog.GAME_DAY_COUNT));
        badges.add(new ChallengeBadgeResponse("PERFECT_DAY", anyThreeStars));
        return badges;
    }

    /**
     * Groups by the CURRENT catalog area for each result's day, never by the
     * area stored on the row. "Por área" answers "how am I doing on X right
     * now" — a question about today's curriculum — so a result completed
     * before a later área rebalance must be re-bucketed under its day's
     * current area, not left under whatever the catalog said at play-time.
     * Trusting the stored value let a played-count exceed an area's current
     * total after any rebalance (see ChallengeDayResultServiceTest).
     */
    private List<ChallengeAreaBreakdownResponse> computeAreaBreakdown(List<ChallengeDayResult> results) {
        Map<String, List<ChallengeDayResult>> byArea = results.stream()
                .collect(Collectors.groupingBy(r -> ChallengeDayCatalog.dayInfo(r.getDay()).area()));

        return ChallengeDayCatalog.AREAS.stream()
                .map(area -> {
                    List<ChallengeDayResult> areaResults = byArea.getOrDefault(area, List.of());
                    double averageStars = areaResults.isEmpty() ? 0.0
                            : areaResults.stream().mapToInt(ChallengeDayResult::getStars).average().orElse(0.0);
                    return new ChallengeAreaBreakdownResponse(area, areaResults.size(), averageStars);
                })
                .toList();
    }

    private ChallengeDayResultResponse toResponse(ChallengeDayResult result) {
        return new ChallengeDayResultResponse(
                result.getDay(),
                result.getArea(),
                result.getMistakes(),
                result.getTotalAttempts(),
                result.getStars(),
                result.getPlayedAt());
    }
}
