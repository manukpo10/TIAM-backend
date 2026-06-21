package com.tiam.patient.dto;

import java.time.Instant;
import java.time.LocalDate;

public record PatientResponse(
    Long id,
    String fullName,
    LocalDate birthDate,
    String diagnosis,
    String notes,
    Long professionalId,
    Instant createdAt,
    Instant lastSessionAt,
    boolean homeSubscriptionActive,
    String playToken
) {}
