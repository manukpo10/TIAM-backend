package com.tiam.session.dto;

import java.util.List;

public record SessionExerciseDto(
    Long exerciseId,
    String title,
    List<String> cognitiveAreaSlugs,
    String difficulty,
    String materialType
) {}
