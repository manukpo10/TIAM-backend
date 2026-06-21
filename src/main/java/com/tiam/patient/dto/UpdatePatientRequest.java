package com.tiam.patient.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record UpdatePatientRequest(
    @NotBlank @Size(max = 255) String fullName,
    @NotNull LocalDate birthDate,
    String diagnosis,
    String notes
) {}
