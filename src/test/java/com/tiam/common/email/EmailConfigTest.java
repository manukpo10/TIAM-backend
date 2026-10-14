package com.tiam.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import com.tiam.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class EmailConfigTest {

    private final EmailConfig config = new EmailConfig();

    private static EmailProperties properties(String apiKey, String from) {
        EmailProperties properties = new EmailProperties();
        properties.setResendApiKey(apiKey);
        properties.setFrom(from);
        return properties;
    }

    @Test
    void emailSender_keyAndFromSet_usesResend() {
        EmailSender sender = config.emailSender(properties("re_key", "TIAM <hola@tiamdigital.com>"));

        assertThat(sender).isInstanceOf(ResendEmailSender.class);
    }

    @Test
    void emailSender_keyUnset_fallsBackToLoggingSoNothingBreaks() {
        assertThat(config.emailSender(properties(null, null))).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void emailSender_keyResolvedToEmptyString_fallsBackToLogging() {
        // An unset env var with the "${RESEND_API_KEY:}" placeholder binds as "", not null.
        assertThat(config.emailSender(properties("", ""))).isInstanceOf(LoggingEmailSender.class);
        assertThat(config.emailSender(properties("   ", "TIAM <hola@tiamdigital.com>")))
                .isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void emailSender_keyWithoutFrom_fallsBackToLogging() {
        assertThat(config.emailSender(properties("re_key", ""))).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void emailSender_disabled_warnsAtStartupWithoutPrintingTheKey() {
        try (LogCapture logs = new LogCapture(EmailConfig.class)) {
            config.emailSender(properties("", ""));
            config.emailSender(properties("re_super_secret", ""));

            assertThat(logs.messages(Level.WARN)).hasSize(2)
                    .anyMatch(line -> line.contains("RESEND_API_KEY is not set"))
                    .anyMatch(line -> line.contains("EMAIL_FROM"));
            assertThat(logs.allMessages()).noneMatch(line -> line.contains("re_super_secret"));
        }
    }

    @Test
    void loggingSender_neverReportsDelivery_andKeepsTheLinkOutOfInfoLogs() {
        EmailMessage message = new EmailMessage("maria.gomez@gmail.com", "Tu Desafío TIAM está listo",
                "<p>html</p>", "https://tiam.example/desafio/secret-token", null);

        try (LogCapture logs = new LogCapture(LoggingEmailSender.class)) {
            boolean sent = new LoggingEmailSender().send(message);

            assertThat(sent).isFalse();
            assertThat(logs.messages(Level.INFO)).singleElement()
                    .satisfies(line -> assertThat(line)
                            .contains("m***@gmail.com")
                            .contains("Tu Desafío TIAM está listo")
                            .doesNotContain("maria.gomez")
                            .doesNotContain("secret-token"));
        }
    }

    @Test
    void emailSender_valuesWithTrailingNewlineOrSpaces_areTrimmedBeforeUse() {
        // A key pasted into a dashboard with a trailing newline used to make the
        // Authorization header invalid (and the resulting exception echoed it).
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(header("Authorization", "Bearer re_key"))
                .andExpect(jsonPath("$.from").value("TIAM <hola@tiamdigital.com>"))
                .andRespond(withSuccess("{\"id\":\"x\"}", MediaType.APPLICATION_JSON));

        EmailSender sender = config.build(
                properties("re_key\r\n", "  TIAM <hola@tiamdigital.com>\n"), builder::build);

        assertThat(sender).isInstanceOf(ResendEmailSender.class);
        assertThat(sender.send(new EmailMessage("buyer@example.com", "s", "<p>h</p>", "t", null))).isTrue();
        server.verify();
    }

    @Test
    void emailSender_keyWithInnerWhitespace_fallsBackToLoggingAndNeverPrintsTheKey() {
        try (LogCapture logs = new LogCapture(EmailConfig.class)) {
            EmailSender sender = config.emailSender(properties("re_bad key", "TIAM <hola@tiamdigital.com>"));

            assertThat(sender).isInstanceOf(LoggingEmailSender.class);
            assertThat(logs.messages(Level.WARN)).singleElement()
                    .satisfies(line -> assertThat(line).contains("RESEND_API_KEY").doesNotContain("re_bad"));
        }
    }

    @Test
    void emailSender_enabledSender_reportsItself_loggingFallbackDoesNot() {
        assertThat(config.emailSender(properties("re_key", "TIAM <hola@tiamdigital.com>")).isEnabled()).isTrue();
        assertThat(config.emailSender(properties("", "")).isEnabled()).isFalse();
    }

    @Test
    void emailAddresses_mask_hidesTheLocalPart() {
        assertThat(EmailAddresses.mask("maria.gomez@gmail.com")).isEqualTo("m***@gmail.com");
        assertThat(EmailAddresses.mask(null)).isEqualTo("<none>");
        assertThat(EmailAddresses.mask("not-an-email")).isEqualTo("***");
    }
}
