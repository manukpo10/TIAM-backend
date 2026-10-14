package com.tiam.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import com.tiam.testsupport.LogCapture;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ResendEmailSenderTest {

    private static final String API_KEY = "re_test_secret_key";
    private static final String FROM = "TIAM <hola@tiamdigital.com>";

    private MockRestServiceServer server;
    private ResendEmailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new ResendEmailSender(builder.build(), API_KEY, FROM);
    }

    private static EmailMessage message(String idempotencyKey) {
        return new EmailMessage("buyer@example.com", "Tu Desafío TIAM está listo",
                "<p>Hola</p>", "Hola", idempotencyKey);
    }

    @Test
    void send_success_postsTheResendPayloadAndReturnsTrue() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Idempotency-Key", "challenge-access/42"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.to.length()").value(1))
                .andExpect(jsonPath("$.to[0]").value("buyer@example.com"))
                .andExpect(jsonPath("$.subject").value("Tu Desafío TIAM está listo"))
                .andExpect(jsonPath("$.html").value("<p>Hola</p>"))
                .andExpect(jsonPath("$.text").value("Hola"))
                .andRespond(withSuccess("{\"id\":\"49a3999c-0ce1\"}", MediaType.APPLICATION_JSON));

        boolean sent = sender.send(message("challenge-access/42"));

        assertThat(sent).isTrue();
        server.verify();
    }

    @Test
    void send_withoutIdempotencyKey_doesNotSendTheHeader() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(headerDoesNotExist("Idempotency-Key"))
                .andRespond(withSuccess("{\"id\":\"x\"}", MediaType.APPLICATION_JSON));

        assertThat(sender.send(message(null))).isTrue();
        server.verify();
    }

    @Test
    void send_resendRejectsTheRequest_returnsFalseWithoutThrowing() {
        // e.g. 403 domain not verified, 422 validation error
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"name\":\"validation_error\",\"message\":\"Invalid `from` field\"}"));

        assertThatCode(() -> assertThat(sender.send(message("k"))).isFalse()).doesNotThrowAnyException();
    }

    @Test
    void send_unauthorized_returnsFalseWithoutThrowing() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThat(sender.send(message("k"))).isFalse();
    }

    @Test
    void send_resendIsDown_returnsFalseWithoutThrowing() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(sender.send(message("k"))).isFalse();
    }

    @Test
    void send_rateLimited_returnsFalseWithoutThrowing() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(sender.send(message("k"))).isFalse();
    }

    @Test
    void send_connectionFailureOrTimeout_returnsFalseWithoutThrowing() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withException(new IOException("Connection reset / read timed out")));

        assertThat(sender.send(message("k"))).isFalse();
    }

    @Test
    void send_exceptionThatEchoesTheAuthorizationHeader_neverLeaksTheKeyToTheLog() {
        // The JDK HTTP client rejects a malformed header with an IllegalArgumentException
        // whose message quotes the header value — i.e. "Bearer <the whole key>".
        String leakyKey = "re_leaky_key_123";
        RestClient failingClient = RestClient.builder()
                .requestFactory((uri, httpMethod) -> {
                    throw new IllegalArgumentException(
                            "invalid header value: \"Bearer " + leakyKey + "\n\"");
                })
                .build();
        ResendEmailSender leaky = new ResendEmailSender(failingClient, leakyKey, FROM);

        try (LogCapture logs = new LogCapture(ResendEmailSender.class)) {
            boolean sent = leaky.send(message("k"));

            assertThat(sent).isFalse();
            assertThat(logs.messages(Level.ERROR)).singleElement().satisfies(line -> assertThat(line)
                    .contains("IllegalArgumentException")
                    .doesNotContain(leakyKey)
                    .doesNotContain("re_leaky")
                    .contains("Bearer ***"));
        }
    }

    @Test
    void redact_removesTheKeyAndAnyBearerCredentialAndBoundsTheLength() {
        assertThat(ResendEmailSender.redact("bad header Bearer re_abc123 more", "re_abc123"))
                .isEqualTo("bad header Bearer *** more");
        assertThat(ResendEmailSender.redact("key=re_abc123 leaked", "re_abc123")).isEqualTo("key=*** leaked");
        assertThat(ResendEmailSender.redact("authorization: bearer OTHER_TOKEN", "re_abc123"))
                .isEqualTo("authorization: Bearer ***");
        assertThat(ResendEmailSender.redact(null, "re_abc123")).isEmpty();
        assertThat(ResendEmailSender.redact("x".repeat(2000), "re_abc123").length()).isLessThanOrEqualTo(501);
    }

    @Test
    void send_failure_neverLogsTheApiKeyOrTheFullRecipient() {
        server.expect(requestTo("https://api.resend.com/emails"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"The tiamdigital.com domain is not verified.\"}"));

        try (LogCapture logs = new LogCapture(ResendEmailSender.class)) {
            sender.send(message("k"));

            assertThat(logs.messages(Level.ERROR)).hasSize(1);
            assertThat(logs.allMessages())
                    .noneMatch(line -> line.contains(API_KEY))
                    .noneMatch(line -> line.contains("buyer@example.com"))
                    .anyMatch(line -> line.contains("403") && line.contains("not verified"));
        }
    }
}
