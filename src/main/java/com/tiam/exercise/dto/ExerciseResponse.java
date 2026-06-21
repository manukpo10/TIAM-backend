package com.tiam.exercise.dto;

import com.tiam.cognitivearea.dto.CognitiveAreaResponse;
import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.domain.ExerciseStatus;
import com.tiam.exercise.domain.MaterialType;
import java.time.Instant;
import java.util.Set;

public record ExerciseResponse(
    Long id,
    String title,
    String description,
    String instructions,
    DifficultyLevel difficulty,
    MaterialType materialType,
    String fileUrl,
    String previewImageUrl,
    ExerciseStatus status,
    Set<CognitiveAreaResponse> cognitiveAreas,
    Long ownerId,
    Instant createdAt,
    Instant updatedAt
) {}
