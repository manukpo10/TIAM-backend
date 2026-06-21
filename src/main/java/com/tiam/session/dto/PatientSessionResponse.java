package com.tiam.session.dto;

import com.tiam.session.domain.SessionStatus;
import java.time.Instant;
import java.util.List;

public record PatientSessionResponse(
    Long id,
    Long patientId,
    Long professionalId,
    String title,
    List<SessionExerciseDto> exercises,
    Instant scheduledDate,
    String notes,
    SessionStatus status
) {}
