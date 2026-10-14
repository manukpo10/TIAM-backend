package com.tiam.pack.domain;

/**
 * Something only the owner can resolve (a payment that does not match its order, a double payment, a month
 * that could not be granted). Published from inside payment transactions and emailed only AFTER they commit,
 * so a slow email provider never holds a row lock or a pooled database connection.
 */
public record PackOwnerAlert(String subject, String body) {
}
