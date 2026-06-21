package com.tiam.play.dto;

public record PlaySessionResponse(
    String patientFirstName,
    MemoryExercise exercise,
    boolean subscriptionActive
) {}
