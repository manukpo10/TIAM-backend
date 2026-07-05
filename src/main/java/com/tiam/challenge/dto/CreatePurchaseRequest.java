package com.tiam.challenge.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreatePurchaseRequest(
        @NotBlank String buyerName,
        @NotBlank String phone,
        @Email String email) {
}
