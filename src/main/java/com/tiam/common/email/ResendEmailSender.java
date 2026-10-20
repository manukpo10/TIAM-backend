package com.tiam.common.email;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link EmailSender} backed by Resend's HTTP API ({@code POST /emails}, Bearer key).
 *
 * <p>The {@link RestClient} is injected already carrying connect/read timeouts (see
 * {@link EmailConfig}); this class only knows the Resend wire format. Every failure
 * mode is caught and reported as "not delivered" — it must never break the caller.
 */
@Slf4j
public class ResendEmailSender implements EmailSender {

    static final String SEND_URL = "https://api.resend.com/emails";
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String USER_AGENT = "tiam-backend";
    private static final int MAX_LOGGED_BODY_CHARS = 500;
    private static final Pattern BEARER_CREDENTIAL = Pattern.compile("(?i)bearer\\s+\\S+");

    private final RestClient restClient;
    private final String apiKey;
    private final String from;
    /** Where replies go; null = no Reply-To is sent. Only ever a plain address (see {@link EmailAddresses#plainAddress}). */
    private final String replyTo;

    public ResendEmailSender(RestClient restClient, String apiKey, String from) {
        this(restClient, apiKey, from, null);
    }

    /**
     * @param replyTo where a reply to ANY email sent through here goes (the {@code from} address has no inbox); a blank
     *                or non-plain value is ignored, so nothing can be injected into the header
     */
    public ResendEmailSender(RestClient restClient, String apiKey, String from, String replyTo) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.from = from;
        this.replyTo = EmailAddresses.plainAddress(replyTo).orElse(null);
    }

    @Override
    public boolean send(EmailMessage message) {
        String recipient = EmailAddresses.mask(message.to());
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(SEND_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .contentType(MediaType.APPLICATION_JSON);
            if (message.idempotencyKey() != null) {
                // Resend drops a repeat of the same key for 24h, so even a retried
                // request can never produce a second email.
                request.header(IDEMPOTENCY_HEADER, message.idempotencyKey());
            }

            ResponseEntity<String> response = request.body(payload(message)).retrieve().toEntity(String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Resend answered HTTP {} for email to {} — treating as not delivered",
                        response.getStatusCode().value(), recipient);
                return false;
            }
            log.info("Email accepted by Resend: to={} response={}", recipient, response.getBody());
            return true;
        } catch (RestClientResponseException e) {
            log.error("Resend rejected the email to {} (HTTP {}): {}", recipient,
                    e.getStatusCode().value(), truncate(e.getResponseBodyAsString()));
            return false;
        } catch (Exception e) {
            // Timeouts, DNS/connection failures, serialization problems... The message is
            // redacted because some JDK/HTTP-client exceptions echo the offending header
            // value (e.g. "invalid header value: Bearer re_...") — the API key must never
            // reach the log.
            log.error("Could not send the email to {} through Resend: {}: {}", recipient,
                    e.getClass().getSimpleName(), redact(e.getMessage(), apiKey));
            return false;
        }
    }

    /** Removes the API key and any "Bearer ..." credential from an exception message, and bounds its size. */
    static String redact(String message, String apiKey) {
        if (message == null) {
            return "";
        }
        String safe = message;
        if (apiKey != null && !apiKey.isBlank()) {
            safe = safe.replace(apiKey.strip(), "***");
        }
        safe = BEARER_CREDENTIAL.matcher(safe).replaceAll("Bearer ***");
        return truncate(safe);
    }

    private Map<String, Object> payload(EmailMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from);
        body.put("to", List.of(message.to()));
        if (replyTo != null) {
            body.put("reply_to", replyTo);
        }
        body.put("subject", message.subject());
        body.put("html", message.html());
        body.put("text", message.text());
        return body;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_LOGGED_BODY_CHARS ? value : value.substring(0, MAX_LOGGED_BODY_CHARS) + "…";
    }
}
