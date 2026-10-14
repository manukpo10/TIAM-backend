package com.tiam.pack.dto;

import java.time.Instant;

/** Proof that the regret request was recorded: the reference to quote and when it was received. */
public record RegretReceiptResponse(String reference, Instant receivedAt) {
}
