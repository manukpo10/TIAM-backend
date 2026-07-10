package com.tiam.challenge.dto;

import java.util.List;

public record ChallengeProgressResponse(
        List<ChallengeDayResultResponse> days,
        ChallengeStreakResponse streak,
        List<ChallengeBadgeResponse> badges,
        List<ChallengeAreaBreakdownResponse> areaBreakdown) {
}
