package com.tiam.challenge.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * A parsed inbound WhatsApp text message, extracted from Meta's webhook payload.
 * Kept separate from the controller so this JSON navigation is unit-testable
 * without MockMvc (no controller-test precedent exists in this codebase).
 */
public record WhatsAppInboundMessage(String from, String textBody) {

    public static Optional<WhatsAppInboundMessage> parse(String rawJsonBody, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(rawJsonBody);
            JsonNode message = root.path("entry").path(0)
                    .path("changes").path(0)
                    .path("value").path("messages").path(0);

            if (message.isMissingNode()) {
                // No "messages" key (e.g. a "statuses" delivery/read receipt payload),
                // or an empty entry/changes array.
                return Optional.empty();
            }

            JsonNode typeNode = message.path("type");
            if (!typeNode.isTextual() || !"text".equals(typeNode.asText())) {
                return Optional.empty();
            }

            JsonNode bodyNode = message.path("text").path("body");
            JsonNode fromNode = message.path("from");
            if (!bodyNode.isTextual() || !fromNode.isTextual()) {
                return Optional.empty();
            }

            return Optional.of(new WhatsAppInboundMessage(fromNode.asText(), bodyNode.asText()));
        } catch (Exception e) {
            // Malformed JSON or any unexpected navigation failure — the controller must
            // always ack with 200, so parsing failures are silently treated as "no message".
            return Optional.empty();
        }
    }
}
