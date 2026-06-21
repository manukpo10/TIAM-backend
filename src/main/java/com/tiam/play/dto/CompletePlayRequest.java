package com.tiam.play.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CompletePlayRequest(
    @NotNull @Min(0) Integer moves,
    @NotNull @Min(0) Integer durationSeconds
) {}
