package com.tiam.common.email;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transactional email settings, fed only from environment variables
 * ({@code RESEND_API_KEY}, {@code EMAIL_FROM}). Deliberately no {@code toString}:
 * the API key must never end up in a log line.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.email")
public class EmailProperties {

    /** Resend API key. Blank = no provider: emails are only logged. */
    private String resendApiKey;

    /** Sender shown to the buyer, e.g. {@code TIAM <hola@tiamdigital.com>}. */
    private String from;
}
