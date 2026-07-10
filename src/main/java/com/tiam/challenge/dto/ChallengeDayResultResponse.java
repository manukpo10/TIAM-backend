package com.tiam.challenge.dto;

import java.time.Instant;

public record ChallengeDayResultResponse(
        int day,
        String area,
        int mistakes,
        int totalAttempts,
        int stars,
        Instant playedAt) {
}
