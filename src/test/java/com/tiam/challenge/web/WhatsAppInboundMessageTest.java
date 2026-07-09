package com.tiam.challenge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppInboundMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parse_validTextMessage_extractsFromAndBody() {
        String json = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [
                    {
                      "id": "102290129340398",
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "metadata": {
                              "display_phone_number": "15550559999",
                              "phone_number_id": "106540352242922"
                            },
                            "contacts": [
                              { "profile": { "name": "Manuel" }, "wa_id": "5491122334455" }
                            ],
                            "messages": [
                              {
                                "from": "5491122334455",
                                "id": "wamid.HBgLNTQ5MTEyMjMzNDQ1NRUCABIYFg==",
                                "timestamp": "1706000000",
                                "text": { "body": "Hola" },
                                "type": "text"
                              }
                            ]
                          },
                          "field": "messages"
                        }
                      ]
                    }
                  ]
                }
                """;

        Optional<WhatsAppInboundMessage> result = WhatsAppInboundMessage.parse(json, objectMapper);

        assertThat(result).isPresent();
        assertThat(result.get().from()).isEqualTo("5491122334455");
        assertThat(result.get().textBody()).isEqualTo("Hola");
    }

    @Test
    void parse_statusUpdatePayload_returnsEmpty() {
        String json = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [
                    {
                      "id": "102290129340398",
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "metadata": {
                              "display_phone_number": "15550559999",
                              "phone_number_id": "106540352242922"
                            },
                            "statuses": [
                              {
                                "id": "wamid.HBgLNTQ5MTEyMjMzNDQ1NRUCABIYFg==",
                                "status": "delivered",
                                "timestamp": "1706000000",
                                "recipient_id": "5491122334455"
                              }
                            ]
                          },
                          "field": "messages"
                        }
                      ]
                    }
                  ]
                }
                """;

        Optional<WhatsAppInboundMessage> result = WhatsAppInboundMessage.parse(json, objectMapper);

        assertThat(result).isEmpty();
    }

    @Test
    void parse_nonTextMessageType_returnsEmpty() {
        String json = """
                {
                  "object": "whatsapp_business_account",
                  "entry": [
                    {
                      "id": "102290129340398",
                      "changes": [
                        {
                          "value": {
                            "messaging_product": "whatsapp",
                            "messages": [
                              {
                                "from": "5491122334455",
                                "id": "wamid.abc123",
                                "timestamp": "1706000000",
                                "type": "image",
                                "image": {
                                  "mime_type": "image/jpeg",
                                  "sha256": "abc123",
                                  "id": "img-id-123"
                                }
                              }
                            ]
                          },
                          "field": "messages"
                        }
                      ]
                    }
                  ]
                }
                """;

        Optional<WhatsAppInboundMessage> result = WhatsAppInboundMessage.parse(json, objectMapper);

        assertThat(result).isEmpty();
    }

    @Test
    void parse_malformedJson_returnsEmptyNotThrows() {
        String malformed = "{ this is not valid json ";

        Optional<WhatsAppInboundMessage> result = WhatsAppInboundMessage.parse(malformed, objectMapper);

        assertThat(result).isEmpty();
    }

    @Test
    void parse_emptyEntryArray_returnsEmpty() {
        String json = """
                {
                  "object": "whatsapp_business_account",
                  "entry": []
                }
                """;

        Optional<WhatsAppInboundMessage> result = WhatsAppInboundMessage.parse(json, objectMapper);

        assertThat(result).isEmpty();
    }
}
