package com.tiam.pack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The "botón de arrepentimiento" form. Only the name and the email are required; the order number and the
 * reason are optional (the buyer may not remember the number, and nobody has to justify a regret).
 *
 * @param orderRef free text the buyer typed ("42", "#42", "TIAM-42"); linked to an order only if it matches
 *                 an order bought with the same email
 */
public record RegretRequest(
        @NotBlank @Size(min = 2, max = 255) String fullName,
        @NotBlank @Size(max = 254) String email,
        @Size(max = 100) String orderRef,
        @Size(max = 2000) String reason) {
}
