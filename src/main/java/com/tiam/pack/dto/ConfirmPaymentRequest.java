package com.tiam.pack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The Mercado Pago payment id the buyer's return page received; it is only a hint to go and ask Mercado Pago. */
public record ConfirmPaymentRequest(
        @NotBlank @Pattern(regexp = "\\d{1,18}", message = "paymentId must be numeric") String paymentId) {
}
