package com.tiam.play.dto;

import java.time.Instant;

public record HomeExerciseResultResponse(
    Long id,
    String exerciseType,
    String exerciseTitle,
    Instant completedAt,
    boolean completed,
    int moves,
    int durationSeconds
) {}
