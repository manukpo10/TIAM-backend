package com.tiam.common.email;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.tiam.testsupport.LogCapture;
import org.junit.jupiter.api.Test;

class LoggingEmailServiceTest {

    @Test
    void sendPasswordReset_neverLogsTheResetToken() {
        String token = "5b1f0a2e-9c3d-4f7a-8e21-reset-token-value";

        try (LogCapture logs = new LogCapture(LoggingEmailService.class)) {
            new LoggingEmailService().sendPasswordReset("maria.gomez@gmail.com", token);

            assertThat(logs.events()).hasSize(1);
            // Not in the formatted line, and not hiding in the raw arguments either.
            assertThat(logs.allMessages()).noneMatch(line -> line.contains(token));
            assertThat(logs.events().stream().map(ILoggingEvent::getArgumentArray).filter(args -> args != null)
                    .flatMap(java.util.Arrays::stream)).noneMatch(arg -> token.equals(String.valueOf(arg)));
            assertThat(logs.messages(Level.INFO)).singleElement()
                    .satisfies(line -> assertThat(line)
                            .contains("Password reset requested")
                            .contains("m***@gmail.com")
                            .doesNotContain("maria.gomez"));
        }
    }
}
