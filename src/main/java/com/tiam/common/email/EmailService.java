package com.tiam.common.email;

public interface EmailService {

    void sendPasswordReset(String toEmail, String resetToken);
}
