package com.tiam.common.email;

/**
 * Transport for transactional email. The provider behind it (Resend today) is an
 * implementation detail chosen in {@link EmailConfig}.
 */
public interface EmailSender {

    /**
     * Hands the message to the email provider.
     *
     * <p>Never throws: callers run next to payment handling, where a delivery
     * problem must be logged and ignored, not propagated.
     *
     * @return true only if the provider accepted the message; false if it was not
     *         delivered for any reason (provider not configured, HTTP error,
     *         timeout, ...)
     */
    boolean send(EmailMessage message);

    /**
     * Whether this sender can really deliver mail. False for the logging fallback, so
     * callers can skip work (e.g. the retry job) that would only produce log noise.
     */
    default boolean isEnabled() {
        return true;
    }
}
