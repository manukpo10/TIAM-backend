package com.tiam.subscription.service;

import java.math.BigDecimal;

/** One line of a Checkout Pro preference (one product the buyer sees on the payment screen). */
public record CheckoutLine(String id, String title, String description, BigDecimal unitPrice) {
}
