package com.tiam.challenge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.common.util.PhoneNumberUtil;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppService {

    private static final String GRAPH_API_BASE = "https://graph.facebook.com/v21.0";

    private final WhatsAppProperties whatsAppProperties;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;

    public boolean isConfigured() {
        return StringUtils.hasText(whatsAppProperties.getAccessToken())
                && StringUtils.hasText(whatsAppProperties.getPhoneNumberId());
    }

    /**
     * Sends a free-form WhatsApp text message. Never throws — this is always called
     * from the webhook handler, which must return HTTP 200 to Meta regardless of
     * whether the outbound send actually succeeds.
     */
    public void sendTextMessage(String toNormalizedPhone, String body) {
        if (!isConfigured()) {
            log.warn("WhatsApp is not configured — skipping send to {}", toNormalizedPhone);
            return;
        }
        try {
            // toWhatsAppSendFormat and buildPayload both live inside the try (not
            // hoisted above it) because either can throw — toWhatsAppSendFormat
            // rejects malformed input (e.g. an empty "from" from a forged/degenerate
            // webhook payload) and buildPayload declares a checked exception. Both
            // must be covered by the catch below to honor this method's "never
            // throws" contract for real, not just because today's only caller
            // happens to wrap it in its own try/catch too.
            String to = PhoneNumberUtil.toWhatsAppSendFormat(toNormalizedPhone);
            String jsonPayload = buildPayload(to, body);
            URI uri = URI.create(GRAPH_API_BASE + "/" + whatsAppProperties.getPhoneNumberId() + "/messages");
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + whatsAppProperties.getAccessToken())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("WhatsApp send failed (HTTP {}): {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Error sending WhatsApp message to {}: {}", toNormalizedPhone, e.getMessage(), e);
        }
    }

    // package-private, pure, unit-testable without hitting the network
    String buildPayload(String to, String body) throws JsonProcessingException {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("body", body);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", to);
        payload.put("type", "text");
        payload.put("text", text);

        return objectMapper.writeValueAsString(payload);
    }
}
