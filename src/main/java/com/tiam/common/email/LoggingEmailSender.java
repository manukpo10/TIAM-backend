package com.tiam.common.email;

import lombok.extern.slf4j.Slf4j;

/**
 * Fallback used when no email provider is configured (RESEND_API_KEY unset): it
 * delivers nothing and says so in the log, so the rest of the app keeps working.
 * Always reports "not delivered", which keeps {@code email_sent_at} NULL so the
 * owner can later tell which buyers never got an email.
 */
@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public boolean send(EmailMessage message) {
        // The body carries the buyer's personal access link, so it is only logged at
        // DEBUG; INFO (the production level) shows just who and what.
        log.info("Email NOT sent (no email provider configured): to={} subject=\"{}\"",
                EmailAddresses.mask(message.to()), message.subject());
        log.debug("Unsent email text body:\n{}", message.text());
        return false;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
