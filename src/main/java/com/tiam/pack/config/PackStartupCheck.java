package com.tiam.pack.config;

import com.tiam.subscription.config.MercadoPagoProperties;
import com.tiam.subscription.config.PlansProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * One loud block in the boot log when pack sales are switched ON with a setting that would make a sale
 * fail or a buyer receive a dead link. It changes no behaviour and says nothing while sales are off.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackStartupCheck {

    private final PackProperties properties;
    private final PlansProperties plans;
    private final MercadoPagoProperties mercadoPago;

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        if (!properties.isSalesEnabled()) {
            return;
        }
        log.info("Pack sales are ENABLED (PACK_SALES_ENABLED=true)");

        if (!StringUtils.hasText(mercadoPago.getAccessToken())) {
            log.error("PACK_SALES_ENABLED=true but MP_ACCESS_TOKEN is not set: every checkout will be refused as closed.");
        }
        if (!StringUtils.hasText(plans.getNotificationUrl())) {
            log.error("PACK_SALES_ENABLED=true but MP_NOTIFICATION_URL is empty: Mercado Pago will not notify payments, "
                    + "so orders are only confirmed by the buyer's return page or the reconciliation job.");
        }
        if (!StringUtils.hasText(mercadoPago.getWebhookSecret())) {
            log.error("PACK_SALES_ENABLED=true but MP_WEBHOOK_SECRET is not set: payment notifications are not "
                    + "signature-checked. Set it before taking real payments.");
        }
        requirePublicHttps("PACK_THANKYOU_URL", properties.getThankyouUrl());
        requirePublicHttps("PACK_DOWNLOAD_BASE_URL", properties.getDownloadBaseUrl());
        requirePublicHttps("PACK_LANDING_BASE_URL", properties.getLandingBaseUrl());

        if (!StringUtils.hasText(properties.effectiveOwnerNotifyTo())) {
            log.warn("PACK_OWNER_NOTIFY_TO (and PACK_REGRET_NOTIFY_TO) are empty: amount mismatches, double payments "
                    + "and regret requests will only be written to the log, nobody will be emailed.");
        }
    }

    private static boolean isPublicHttps(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            URI uri = new URI(url.strip());
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return !(lower.equals("localhost") || lower.endsWith(".localhost") || lower.startsWith("127.")
                    || lower.equals("0.0.0.0") || lower.equals("[::1]") || lower.equals("::1"));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static void requirePublicHttps(String variable, String value) {
        if (!isPublicHttps(value)) {
            log.error("PACK_SALES_ENABLED=true but {} is \"{}\" (empty, localhost or not https): Mercado Pago or the "
                    + "buyer would be sent to an address that does not work.", variable, value);
        }
    }
}
