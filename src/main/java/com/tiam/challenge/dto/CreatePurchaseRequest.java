package com.tiam.challenge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreatePurchaseRequest(
        @NotBlank String buyerName,
        @NotBlank String phone,
        @Email String email,
        // Which 30-day catalog to purchase (1 = original, 2 = "Mes 2"). Optional —
        // null/absent means month 1, so existing callers that don't send it yet
        // keep working unchanged.
        Integer challengeMonth) {
}
