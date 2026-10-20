package com.tiam.common.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import com.tiam.testsupport.LogCapture;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Buyers answer the emails they get, and the sender ({@code hola@tiamdigital.com}) has no inbox: every email goes out with
 * a Reply-To. It is {@code EMAIL_REPLY_TO}, else the owner's address ({@code PACK_OWNER_NOTIFY_TO}), else none, and it is
 * always a plain address (a header cannot be injected through it).
 */
class EmailReplyToTest {

    private static final String FROM = "TIAM <hola@tiamdigital.com>";

    // --- what counts as a plain email address -------------------------------------------------------------------------

    @Test
    void plainAddresses_areAccepted_andTrimmed() {
        assertThat(EmailAddresses.plainAddress("owner@gmail.com")).contains("owner@gmail.com");
        assertThat(EmailAddresses.plainAddress("  ana.gomez+tiam@example.com.ar  ")).contains("ana.gomez+tiam@example.com.ar");
        assertThat(EmailAddresses.plainAddress("O'Brien_1@sub.example.org")).contains("O'Brien_1@sub.example.org");
        assertThat(EmailAddresses.plainAddress("owner@gmail.com\n")).as("a pasted trailing newline").contains("owner@gmail.com");
    }

    @Test
    void anythingElse_isRejected_includingEveryWayToInjectAHeader() {
        for (String bad : new String[] {null, "", "   ", "a@b", "a b@c.com", "a@@b.com", "@b.com", "a@.com", "a@b.c",
                "Ana <ana@example.com>", "<ana@example.com>", "ana@example.com, otro@example.com",
                "ana@example.com;otro@example.com", "\"ana\"@example.com", "ana@example.com\r\nBcc: x@y.com",
                "ana@example.com\nBcc: x@y.com", "ana@example.com\rX: y", "ana@exam\u0000ple.com", "a@b.com>",
                "x".repeat(250) + "@example.com"}) {
            assertThat(EmailAddresses.plainAddress(bad)).as("[%s]", bad == null ? null : bad.replace("\r", "\\r").replace("\n", "\\n"))
                    .isEmpty();
        }
    }

    // --- the sender adds it to the payload ---------------------------------------------------------------------------------

    @Test
    void theSenderPutsTheReplyToInEveryPayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendEmailSender sender = new ResendEmailSender(builder.build(), "re_key", FROM, "owner@gmail.com");
        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(jsonPath("$.reply_to").value("owner@gmail.com"))
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.to[0]").value("buyer@example.com"))
                .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));

        assertThat(sender.send(new EmailMessage("buyer@example.com", "Hola", "<p>x</p>", "x", "k/1"))).isTrue();
        server.verify();
    }

    @Test
    void withoutAReplyTo_thePayloadHasNone() {
        for (String none : new String[] {null, "", "   "}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            ResendEmailSender sender = new ResendEmailSender(builder.build(), "re_key", FROM, none);
            server.expect(requestTo("https://api.resend.com/emails"))
                    .andExpect(jsonPath("$.reply_to").doesNotExist())
                    .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));

            assertThat(sender.send(new EmailMessage("buyer@example.com", "Hola", "<p>x</p>", "x", null))).isTrue();
            server.verify();
        }
    }

    @Test
    void theSenderRefusesToCarryAnythingButAPlainAddress_evenIfHandedOne() {
        for (String hostile : new String[] {"a@b.com\r\nBcc: x@y.com", "Ana <a@b.com>", "a@b.com,c@d.com"}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            ResendEmailSender sender = new ResendEmailSender(builder.build(), "re_key", FROM, hostile);
            server.expect(requestTo("https://api.resend.com/emails"))
                    .andExpect(jsonPath("$.reply_to").doesNotExist())
                    .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));

            assertThat(sender.send(new EmailMessage("buyer@example.com", "Hola", "<p>x</p>", "x", null))).isTrue();
            server.verify();
        }
    }

    // --- which address is used --------------------------------------------------------------------------------------------

    @Test
    void emailReplyTo_winsOverTheOwnerAddress() {
        assertThat(resolve("soporte@tiamdigital.com", "owner@gmail.com")).contains("soporte@tiamdigital.com");
    }

    @Test
    void whenEmailReplyToIsUnset_theOwnerAddressIsUsed() {
        assertThat(resolve(null, "owner@gmail.com")).contains("owner@gmail.com");
        assertThat(resolve("", "owner@gmail.com")).contains("owner@gmail.com");
        assertThat(resolve("   ", " owner@gmail.com ")).contains("owner@gmail.com");
    }

    @Test
    void whenBothAreEmpty_noReplyToIsSent() {
        assertThat(resolve(null, null)).isEmpty();
        assertThat(resolve("", "")).isEmpty();
        assertThat(resolve("  ", "  ")).isEmpty();
    }

    @Test
    void anInvalidEmailReplyTo_isLoggedAndSkipped_theOwnerAddressTakesOver() {
        try (LogCapture logs = new LogCapture(EmailConfig.class)) {
            assertThat(resolve("Ana <a@b.com>", "owner@gmail.com")).contains("owner@gmail.com");

            assertThat(logs.messages(Level.WARN)).anyMatch(m -> m.contains("EMAIL_REPLY_TO") && m.contains("not a plain email address"));
            assertThat(logs.messages(Level.WARN)).noneMatch(m -> m.contains("Ana <a@b.com>")); // never echo it
        }
    }

    @Test
    void anInvalidOwnerAddress_isLoggedAndNoReplyToIsSent() {
        try (LogCapture logs = new LogCapture(EmailConfig.class)) {
            assertThat(resolve(null, "a@b.com,c@d.com")).isEmpty();

            assertThat(logs.messages(Level.WARN)).anyMatch(m -> m.contains("PACK_OWNER_NOTIFY_TO") && m.contains("not a plain email address"));
        }
    }

    @Test
    void theBuiltSender_carriesTheResolvedReplyTo() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EmailProperties properties = new EmailProperties();
        properties.setResendApiKey("re_key");
        properties.setFrom(FROM);
        properties.setReplyToFallback("owner@gmail.com"); // PACK_OWNER_NOTIFY_TO, already set in production

        EmailSender sender = new EmailConfig().build(properties, builder::build);

        server.expect(requestTo("https://api.resend.com/emails"))
                .andExpect(jsonPath("$.reply_to").value("owner@gmail.com"))
                .andRespond(withSuccess("{\"id\":\"1\"}", MediaType.APPLICATION_JSON));
        assertThat(sender.send(new EmailMessage("buyer@example.com", "Hola", "<p>x</p>", "x", null))).isTrue();
        server.verify();
    }

    @Test
    void theLoggingFallbackSender_isUnaffected() {
        EmailProperties properties = new EmailProperties();
        properties.setReplyTo("owner@gmail.com");

        assertThat(new EmailConfig().build(properties, RestClient::create)).isInstanceOf(LoggingEmailSender.class);
    }

    private static Optional<String> resolve(String replyTo, String ownerAddress) {
        EmailProperties properties = new EmailProperties();
        properties.setReplyTo(replyTo);
        properties.setReplyToFallback(ownerAddress);
        return EmailConfig.resolveReplyTo(properties);
    }
}
