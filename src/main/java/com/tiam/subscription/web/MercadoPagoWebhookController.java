package com.tiam.subscription.web;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preapproval.Preapproval;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.subscription.config.MercadoPagoProperties;
import com.tiam.subscription.service.MercadoPagoService;
import com.tiam.subscription.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/webhooks")
@RequiredArgsConstructor
public class MercadoPagoWebhookController {

    // MP's x-signature header: "ts=1704908010,v1=618c85345248dd820d5fd456117c2ab2..."
    private static final Pattern SIGNATURE_HEADER_PATTERN = Pattern.compile("ts=(\\d+),v1=([0-9a-fA-F]+)");

    private final MercadoPagoService mercadoPagoService;
    private final SubscriptionService subscriptionService;
    private final ChallengePurchaseService challengePurchaseService;
    private final MercadoPagoProperties mercadoPagoProperties;

    /**
     * Receives Mercado Pago IPN/webhook notifications.
     * Must return 200 quickly — MP retries on any non-200 response.
     * Path: POST /webhooks/mercadopago
     */
    @PostMapping("/mercadopago")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String id,
            @RequestParam(value = "data.id", required = false) String dataId,
            HttpServletRequest request) {

        // Resolve the resource id ONCE, then use that exact value for both the
        // signature check and processing. Resolving it twice (as an earlier version
        // of this method did) let an attacker keep a validly-signed request while
        // smuggling a different id via another field — the signature would pass but
        // a different resource would be processed.
        // Webhooks v1 sends "type"; the older IPN (per-preference notification_url) sends "topic".
        String notificationType = resolveType(payload, StringUtils.hasText(type) ? type : topic);
        String resourceId = resolveId(payload, StringUtils.hasText(id) ? id : dataId);

        if (!isValidSignature(request, resourceId)) {
            log.warn("MP webhook rejected: invalid signature (type={})", notificationType);
            return ResponseEntity.ok().build();
        }

        log.info("MP webhook received: type={} id={}", notificationType, resourceId);

        try {
            processNotification(notificationType, resourceId);
        } catch (Exception e) {
            // Log and ack — never fail a webhook, MP will retry
            log.error("Error processing MP webhook type={} id={}: {}", notificationType, resourceId,
                    e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    /**
     * Validates the x-signature header per Mercado Pago's HMAC-SHA256 scheme:
     * manifest = "id:{data.id};request-id:{x-request-id};ts:{ts};", compared against v1.
     *
     * If MP_WEBHOOK_SECRET is not configured, validation is skipped (with a warning)
     * so existing deployments don't break — set the secret to activate this check.
     */
    private boolean isValidSignature(HttpServletRequest request, String resourceId) {
        String secret = mercadoPagoProperties.getWebhookSecret();
        if (!StringUtils.hasText(secret)) {
            log.warn("MP_WEBHOOK_SECRET is not configured — skipping signature validation. "
                    + "Set it in production to reject forged notifications.");
            return true;
        }

        String signatureHeader = request.getHeader("x-signature");
        String requestId = request.getHeader("x-request-id");

        if (!StringUtils.hasText(signatureHeader) || !StringUtils.hasText(requestId)
                || !StringUtils.hasText(resourceId)) {
            return false;
        }

        Matcher matcher = SIGNATURE_HEADER_PATTERN.matcher(signatureHeader);
        if (!matcher.find()) {
            return false;
        }

        String ts = matcher.group(1);
        String receivedHash = matcher.group(2);
        String manifest = "id:" + resourceId.toLowerCase() + ";request-id:" + requestId + ";ts:" + ts + ";";

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computedHash = mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
            byte[] receivedHashBytes = HexFormat.of().parseHex(receivedHash.toLowerCase());
            // Constant-time comparison — a naive String/Arrays equality check leaks timing
            // information that could help an attacker forge a valid signature byte-by-byte.
            return MessageDigest.isEqual(computedHash, receivedHashBytes);
        } catch (Exception e) {
            log.error("Failed to compute MP webhook signature: {}", e.getMessage(), e);
            return false;
        }
    }

    private void processNotification(String type, String resourceId)
            throws MPException, MPApiException {
        if (!StringUtils.hasText(type) || !StringUtils.hasText(resourceId)) {
            log.debug("MP webhook: empty type or id — acking without processing");
            return;
        }

        switch (type) {
            case "subscription_preapproval" -> handlePreapproval(resourceId);
            case "payment" -> handlePayment(resourceId);
            default -> log.debug("MP webhook: unrecognized type={} — acking", type);
        }
    }

    private void handlePreapproval(String preapprovalId) throws MPException, MPApiException {
        if (!mercadoPagoService.isConfigured()) {
            log.warn("MP webhook received but MP is not configured — skipping");
            return;
        }

        Preapproval preapproval = mercadoPagoService.getPreapproval(preapprovalId);
        String externalReference = preapproval.getExternalReference();
        String status = preapproval.getStatus();

        log.info("MP preapproval id={} status={} externalReference={}",
                preapprovalId, status, externalReference);

        if (!StringUtils.hasText(externalReference)) {
            log.warn("MP preapproval {} has no externalReference — cannot correlate", preapprovalId);
            return;
        }

        Long subscriptionId;
        try {
            subscriptionId = Long.parseLong(externalReference);
        } catch (NumberFormatException e) {
            log.warn("Invalid externalReference={} — not a Long", externalReference);
            return;
        }

        if ("authorized".equals(status)) {
            // Determine period end from next payment date, or fallback to 30 days
            Instant periodEnd = preapproval.getNextPaymentDate() != null
                    ? preapproval.getNextPaymentDate().toInstant()
                    : Instant.now().plusSeconds(30L * 24 * 60 * 60);

            subscriptionService.activateSubscription(subscriptionId, preapprovalId, periodEnd);

        } else if ("cancelled".equals(status) || "paused".equals(status)) {
            subscriptionService.cancelSubscription(subscriptionId);
        } else {
            log.info("MP preapproval {} status={} — no action taken", preapprovalId, status);
        }
    }

    private void handlePayment(String paymentId) throws MPException, MPApiException {
        if (!mercadoPagoService.isConfigured()) {
            log.warn("MP webhook received but MP is not configured — skipping");
            return;
        }

        Payment payment = mercadoPagoService.getPayment(paymentId);
        String externalReference = payment.getExternalReference();
        String status = payment.getStatus();

        log.info("MP payment id={} status={} externalReference={}", paymentId, status, externalReference);

        if (!StringUtils.hasText(externalReference)) {
            log.warn("MP payment {} has no externalReference — cannot correlate", paymentId);
            return;
        }

        Long purchaseId;
        try {
            purchaseId = Long.parseLong(externalReference);
        } catch (NumberFormatException e) {
            log.warn("Invalid externalReference={} — not a Long", externalReference);
            return;
        }

        if ("approved".equals(status)) {
            challengePurchaseService.markPaid(purchaseId, paymentId);
        } else if ("rejected".equals(status) || "cancelled".equals(status)) {
            challengePurchaseService.markFailed(purchaseId);
        } else {
            log.info("MP payment {} status={} — no action taken", paymentId, status);
        }
    }

    private String resolveType(Map<String, Object> body, String queryType) {
        if (StringUtils.hasText(queryType)) return queryType;
        if (body != null && body.containsKey("type")) return String.valueOf(body.get("type"));
        return null;
    }

    private String resolveId(Map<String, Object> body, String queryId) {
        if (StringUtils.hasText(queryId)) return queryId;
        if (body != null) {
            // MP sends { "data": { "id": "..." }, "type": "..." }
            Object data = body.get("data");
            if (data instanceof Map<?, ?> dataMap && dataMap.containsKey("id")) {
                return String.valueOf(dataMap.get("id"));
            }
        }
        return null;
    }
}
