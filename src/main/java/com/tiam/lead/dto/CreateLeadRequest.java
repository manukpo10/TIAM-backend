package com.tiam.lead.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for capturing a lead from the public lead magnet.
 * {@code consent} must be true — the user has to accept the privacy notice.
 *
 * <p>{@code website} and {@code elapsedMs} are bot traps sent by the form, not data: {@code website} is a hidden
 * honeypot input that a person never sees (it must come empty) and {@code elapsedMs} is how long the form was on
 * screen before it was sent. Both are optional here so an old cached form still deserializes; the service decides.
 * {@code website} has no size limit on purpose: a violation would answer 400 naming the field and tell the bot
 * what stopped it (it is never stored).
 */
public record CreateLeadRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @Size(max = 255) String name,
    @Size(max = 100) String source,
    @AssertTrue(message = "Se requiere el consentimiento para continuar") boolean consent,
    String website,
    Long elapsedMs
) {}
