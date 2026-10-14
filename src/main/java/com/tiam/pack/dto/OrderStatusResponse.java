package com.tiam.pack.dto;

/** What the buyer's browser may learn about an order's state. Never exposes internal states. */
public record OrderStatusResponse(String status) {
}
