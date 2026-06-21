package com.tiam.common.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Stub email service — logs the reset token at INFO level.
 * TODO: Replace with a real provider (Resend, SendGrid, or Spring Mail/SMTP).
 */
@Slf4j
@Service
public class LoggingEmailService implements EmailService {

    @Override
    public void sendPasswordReset(String toEmail, String resetToken) {
        log.info("Password reset requested for {} → token: {}", toEmail, resetToken);
    }
}
