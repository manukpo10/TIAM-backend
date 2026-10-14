package com.tiam.common.email;

/**
 * A provider-agnostic transactional email.
 *
 * @param to             single recipient address
 * @param subject        subject line
 * @param html           HTML body
 * @param text           plain-text alternative of the same content
 * @param idempotencyKey optional key that lets the provider drop a duplicate of
 *                       the same logical email (e.g. a retried request); null = none
 */
public record EmailMessage(String to, String subject, String html, String text, String idempotencyKey) {
}
