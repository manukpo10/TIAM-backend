package com.tiam.lead.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for capturing a lead from the public lead magnet.
 * {@code consent} must be true — the user has to accept the privacy notice.
 */
public record CreateLeadRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @Size(max = 255) String name,
    @Size(max = 100) String source,
    @AssertTrue(message = "Se requiere el consentimiento para continuar") boolean consent
) {}
