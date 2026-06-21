package com.tiam.exercise.dto;

import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.domain.ExerciseStatus;
import com.tiam.exercise.domain.MaterialType;
import jakarta.validation.constraints.*;
import java.util.List;

public record AdminExerciseRequest(
    @NotBlank @Size(max = 255) String title,
    @NotBlank String description,
    @NotBlank String instructions,
    @NotNull DifficultyLevel difficulty,
    @NotNull MaterialType materialType,
    @NotEmpty List<Long> cognitiveAreaIds,
    ExerciseStatus status
) {}
