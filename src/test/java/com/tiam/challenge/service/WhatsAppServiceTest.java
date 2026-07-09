package com.tiam.challenge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiam.challenge.config.WhatsAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppServiceTest {

    @Mock WhatsAppProperties whatsAppProperties;

    WhatsAppService service;
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WhatsAppService(whatsAppProperties, objectMapper);
    }

    @Test
    void isConfigured_bothFieldsSet_returnsTrue() {
        when(whatsAppProperties.getAccessToken()).thenReturn("token-123");
        when(whatsAppProperties.getPhoneNumberId()).thenReturn("phone-456");

        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_missingAccessToken_returnsFalse() {
        // getPhoneNumberId() is deliberately NOT stubbed here: isConfigured() uses
        // "&&" short-circuit evaluation, so once getAccessToken() is blank the second
        // check never runs — stubbing it anyway would trip Mockito's strict-stubs
        // UnnecessaryStubbingException.
        when(whatsAppProperties.getAccessToken()).thenReturn("");

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_missingPhoneNumberId_returnsFalse() {
        when(whatsAppProperties.getAccessToken()).thenReturn("token-123");
        when(whatsAppProperties.getPhoneNumberId()).thenReturn("");

        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void sendTextMessage_invalidNormalizedPhone_neverThrows() {
        // isConfigured() must be true to reach the toWhatsAppSendFormat() call —
        // that's what previously could throw IllegalArgumentException on a bad
        // "from" (e.g. an empty string from a malformed/forged webhook payload)
        // *outside* the method's own try/catch. This proves the contract holds
        // without needing to mock the actual HTTP call.
        when(whatsAppProperties.getAccessToken()).thenReturn("token-123");
        when(whatsAppProperties.getPhoneNumberId()).thenReturn("phone-456");

        assertThatCode(() -> service.sendTextMessage("", "hola"))
                .doesNotThrowAnyException();
    }

    @Test
    void buildPayload_producesValidJsonWithExpectedFields() throws Exception {
        String json = service.buildPayload("5491122334455", "Hola, este es tu mensaje");

        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("messaging_product").asText()).isEqualTo("whatsapp");
        assertThat(node.get("to").asText()).isEqualTo("5491122334455");
        assertThat(node.get("type").asText()).isEqualTo("text");
        assertThat(node.get("text").get("body").asText()).isEqualTo("Hola, este es tu mensaje");
    }
}
