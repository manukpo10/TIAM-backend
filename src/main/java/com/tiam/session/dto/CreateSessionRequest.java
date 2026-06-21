package com.tiam.session.dto;

import jakarta.validation.constraints.*;
import java.util.List;

public record CreateSessionRequest(
    @NotNull Long patientId,
    @NotBlank @Size(max = 255) String title,
    String notes,
    @NotEmpty List<SessionExerciseDto> exercises
) {}
