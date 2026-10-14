package com.tiam.common.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Stub email service for password resets: delivers nothing, so the reset flow does not
 * reach users yet.
 *
 * <p>It must NOT log the reset token: the production log level is INFO, and a token in
 * the logs lets anyone with log access take over the account. Wiring this through
 * {@link EmailSender} to really email the reset link is still pending.
 * TODO: Replace with a real provider call (see {@link ResendEmailSender}).
 */
@Slf4j
@Service
public class LoggingEmailService implements EmailService {

    @Override
    public void sendPasswordReset(String toEmail, String resetToken) {
        log.info("Password reset requested for {} — no email was sent (stub); the reset token is deliberately not logged",
                EmailAddresses.mask(toEmail));
    }
}
