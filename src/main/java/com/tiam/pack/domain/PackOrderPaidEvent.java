package com.tiam.pack.domain;

/**
 * Published inside the transaction that moves an order to PAID, exactly once, on the real transition.
 * The access email is sent by a listener that only runs AFTER that transaction commits.
 */
public record PackOrderPaidEvent(Long orderId) {
}
