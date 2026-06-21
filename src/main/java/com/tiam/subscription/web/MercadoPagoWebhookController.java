package com.tiam.subscription.web;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.preapproval.Preapproval;
import com.tiam.subscription.service.MercadoPagoService;
import com.tiam.subscription.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
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

    private final MercadoPagoService mercadoPagoService;
    private final SubscriptionService subscriptionService;

    /**
     * Receives Mercado Pago IPN/webhook notifications.
     * Must return 200 quickly — MP retries on any non-200 response.
     * Path: POST /webhooks/mercadopago
     */
    @PostMapping("/mercadopago")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String id,
            HttpServletRequest request) {

        // MP sends notifications via query params (type, id) and/or JSON body
        String notificationType = resolveType(payload, type);
        String resourceId = resolveId(payload, id);

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

    private void processNotification(String type, String resourceId)
            throws MPException, MPApiException {
        if (!StringUtils.hasText(type) || !StringUtils.hasText(resourceId)) {
            log.debug("MP webhook: empty type or id — acking without processing");
            return;
        }

        switch (type) {
            case "subscription_preapproval" -> handlePreapproval(resourceId);
            case "payment" -> log.info("MP payment notification id={} — not yet handled", resourceId);
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
