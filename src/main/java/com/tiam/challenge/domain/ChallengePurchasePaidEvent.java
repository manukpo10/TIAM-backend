package com.tiam.challenge.domain;

/**
 * Published by {@code ChallengePurchaseService.markPaid} exactly when a purchase
 * moves to PAID for the first time (never for an already-PAID duplicate). Carries
 * only the id: listeners re-read the row after the transaction has committed.
 */
public record ChallengePurchasePaidEvent(Long purchaseId) {
}
