package com.tiam.pack.domain;

public enum PackOrderStatus {
    /** Checkout started, payment not confirmed. */
    PENDING,
    /** Payment confirmed for the right amount: files are deliverable. */
    PAID,
    /** Mercado Pago rejected or cancelled the payment. */
    FAILED,
    /** Refunded or charged back: access is closed. */
    REVOKED,
    /** Paid, but the amount or currency does not match the order: held back for a human. */
    REVIEW
}
