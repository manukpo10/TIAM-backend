package com.tiam.challenge.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CompleteDayRequest(
        @NotNull @Min(0) Integer mistakes,
        @NotNull @Min(1) Integer totalAttempts) {
}
