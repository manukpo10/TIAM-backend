package com.tiam.pack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "Send me my access again". The format of the email is deliberately NOT validated here: the answer is the
 * same 202 for every input, so there is nothing to learn from a rejection.
 */
public record ResendAccessRequest(@NotBlank @Size(max = 254) String email) {
}
