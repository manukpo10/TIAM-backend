package com.tiam.challenge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.challenge.service.WhatsAppService;
import com.tiam.common.util.PhoneNumberUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Handles Meta's WhatsApp Cloud API webhook: the GET verification handshake
 * done once when the webhook URL is registered, and the POST delivery of
 * inbound messages/status updates.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final WhatsAppProperties whatsAppProperties;
    private final WhatsAppService whatsAppService;
    private final ChallengePurchaseService challengePurchaseService;
    private final ObjectMapper objectMapper;

    @GetMapping("/whatsapp")
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.verify_token") String verifyToken,
            @RequestParam("hub.challenge") String challenge) {
        if ("subscribe".equals(mode) && StringUtils.hasText(whatsAppProperties.getVerifyToken())
                && whatsAppProperties.getVerifyToken().equals(verifyToken)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /**
     * Receives inbound WhatsApp messages and status updates.
     * Must always return 200 — Meta retries (and can eventually disable the
     * webhook) on non-200 responses, so all errors are logged and swallowed here.
     */
    @PostMapping("/whatsapp")
    public ResponseEntity<Void> receive(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signatureHeader) {
        try {
            if (!isValidSignature(rawBody, signatureHeader)) {
                log.warn("WhatsApp webhook rejected: invalid signature");
                return ResponseEntity.ok().build();
            }
            WhatsAppInboundMessage.parse(rawBody, objectMapper).ifPresent(msg -> {
                String reply = challengePurchaseService.buildWhatsAppReply(msg.from());
                whatsAppService.sendTextMessage(PhoneNumberUtil.normalize(msg.from()), reply);
            });
        } catch (Exception e) {
            log.error("Error processing WhatsApp webhook: {}", e.getMessage(), e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Validates Meta's X-Hub-Signature-256 header: HMAC-SHA256 of the raw request
     * body, keyed with the app secret. Mirrors the already-precedented
     * MercadoPagoWebhookController#isValidSignature HMAC pattern.
     *
     * If WHATSAPP_APP_SECRET is not configured, validation is skipped (with a
     * warning) so existing deployments don't break — set the secret to activate
     * this check.
     */
    private boolean isValidSignature(String rawBody, String signatureHeader) {
        String secret = whatsAppProperties.getAppSecret();
        if (!StringUtils.hasText(secret)) {
            log.warn("WHATSAPP_APP_SECRET is not configured — skipping signature validation. "
                    + "Set it in production to reject forged webhook deliveries.");
            return true;
        }
        if (!StringUtils.hasText(signatureHeader) || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        String receivedHash = signatureHeader.substring("sha256=".length());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computedHash = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] receivedHashBytes = HexFormat.of().parseHex(receivedHash.toLowerCase());
            // Constant-time comparison — a naive String/Arrays equality check leaks timing
            // information that could help an attacker forge a valid signature byte-by-byte.
            return MessageDigest.isEqual(computedHash, receivedHashBytes);
        } catch (Exception e) {
            log.error("Failed to compute WhatsApp webhook signature: {}", e.getMessage(), e);
            return false;
        }
    }
}
