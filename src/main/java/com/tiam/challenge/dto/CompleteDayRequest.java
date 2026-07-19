package com.tiam.challenge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CompleteDayRequest(
        @NotNull @Min(0) Integer mistakes,
        // 0 is valid: papel-y-lápiz days (6, 12, 18, 30) have no scored attempts and
        // report totalAttempts=0. The service treats that as full participation
        // (3 stars) — rejecting it here 400s those days and silently breaks the streak.
        @NotNull @Min(0) Integer totalAttempts) {
}
