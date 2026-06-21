package com.tiam.play.dto;

import java.util.List;

public record MemoryExercise(
    String type,
    String title,
    String instructions,
    List<MemoryCard> cards
) {}
