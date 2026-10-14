package com.tiam.subscription.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Startup-only visibility for the Mercado Pago webhook secret. It changes no
 * runtime behavior: {@code MercadoPagoWebhookController} still skips signature
 * validation when the secret is unset. This just puts one unmissable WARN in the
 * boot log so the owner can confirm production has it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MercadoPagoWebhookSecretCheck {

    private final MercadoPagoProperties mercadoPagoProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfWebhookSecretMissing() {
        if (!StringUtils.hasText(mercadoPagoProperties.getWebhookSecret())) {
            log.warn("MP_WEBHOOK_SECRET is NOT set: Mercado Pago webhook signature validation is DISABLED, "
                    + "so POST /webhooks/mercadopago accepts unsigned notifications. "
                    + "Set MP_WEBHOOK_SECRET in production (Mercado Pago > Your integrations > Webhooks > secret).");
        }
    }
}
