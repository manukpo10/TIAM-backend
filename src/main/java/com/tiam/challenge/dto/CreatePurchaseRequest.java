package com.tiam.challenge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreatePurchaseRequest(
        @NotBlank String buyerName,
        @NotBlank String phone,
        @Email String email,
        // Which 30-day catalog to purchase (1 = original, 2 = "Mes 2", 3 = "Mes 3").
        // Optional — null/absent means "auto-assign": the buyer's phone's first
        // purchase gets month 1, and a returning phone that already has a PAID
        // purchase for month N gets month N+1, skipping straight to it with no
        // manual selection needed. See
        // ChallengePurchaseService#createPurchase/#nextUnpaidMonth.
        Integer challengeMonth) {
}
