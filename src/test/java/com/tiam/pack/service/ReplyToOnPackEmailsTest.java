package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.ResendEmailSender;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackRegretRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The pack emails a buyer can answer (delivery, "I lost my link", the regret receipt) all leave through the one sender, so
 * each of them carries the Reply-To and never bounces into the inbox-less {@code hola@} address.
 */
class ReplyToOnPackEmailsTest {

    @Test
    void deliveryLostLinkAndRegretReceipt_allCarryTheReplyTo() {
        PackProperties properties = new PackProperties();
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setLandingBaseUrl("https://tiamdigital.com");
        PackAccessEmailComposer composer = new PackAccessEmailComposer(properties, new WhatsAppProperties(),
                org.mockito.Mockito.mock(PackSalesReadiness.class));
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setAccessToken("AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde");
        order.setEdition(PackEdition.CUADERNOS);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        PackRegretRequest regret = new PackRegretRequest();
        regret.setId(12L);
        regret.setFullName("Ana Gómez");
        regret.setEmail("ana@example.com");
        regret.setCreatedAt(java.time.Instant.parse("2026-10-14T15:30:00Z"));

        List<EmailMessage> messages = List.of(
                composer.compose(order, Optional.empty()),
                composer.composeResend("ana@example.com",
                        List.of(new PackAccessEmailComposer.ResendEntry(order, "R".repeat(43), Optional.empty())), "key"),
                new PackRegretEmailComposer().buyerReceipt(regret, "ARR-000001"));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ResendEmailSender sender = new ResendEmailSender(builder.build(), "re_key", "TIAM <hola@tiamdigital.com>", "owner@gmail.com");
        for (int i = 0; i < messages.size(); i++) {
            server.expect(requestTo("https://api.resend.com/emails"))
                    .andExpect(jsonPath("$.reply_to").value("owner@gmail.com"))
                    .andExpect(jsonPath("$.from").value("TIAM <hola@tiamdigital.com>"))
                    .andRespond(withSuccess("{\"id\":\"" + i + "\"}", MediaType.APPLICATION_JSON));
        }

        for (EmailMessage message : messages) {
            assertThat(sender.send(message)).isTrue();
        }
        server.verify();
    }
}
