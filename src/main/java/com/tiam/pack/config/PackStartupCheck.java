package com.tiam.pack.config;

import com.tiam.subscription.config.MercadoPagoProperties;
import com.tiam.subscription.config.PlansProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * One loud block in the boot log when pack sales are switched ON with a setting that would make a sale fail or a
 * buyer receive a dead link, listing exactly what the checkout will refuse until it is fixed (see
 * {@link PackSalesReadiness}). It changes no behaviour itself. With sales off it stays quiet, except for the one
 * thing that is still live: the {@code ?error=cerrado} redirect goes to {@code PACK_LANDING_BASE_URL}, so a
 * wrong value there strands visitors even while nothing is for sale.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PackStartupCheck {

    private final PackProperties properties;
    private final PlansProperties plans;
    private final MercadoPagoProperties mercadoPago;
    private final PackSalesReadiness readiness;

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        if (readiness.devModeIgnored()) {
            log.error("PACK_DEV_MODE=true is set but the prod profile is active: it is IGNORED. "
                    + "Remove PACK_DEV_MODE from this environment.");
        }
        if (!properties.isSalesEnabled()) {
            checkUrlsWhileSalesAreOff();
            return;
        }
        log.info("Pack sales are ENABLED (PACK_SALES_ENABLED=true)");

        if (readiness.devModeActive()) {
            log.warn("PACK_DEV_MODE is ON: checkouts may start with the local file folder, the logging-only email "
                    + "sender and localhost URLs. DEVELOPMENT ONLY - a real buyer would get dead links.");
        } else {
            readiness.problems().forEach(problem -> log.error(
                    "PACK_SALES_ENABLED=true but the shop is NOT ready, every checkout is refused as closed "
                            + "(?error=cerrado) until this is fixed: {}", problem));
        }

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
    }

    private void checkUrlsWhileSalesAreOff() {
        if (!readiness.isProduction()) {
            return; // a developer's machine legitimately points at localhost
        }
        warnIfNotPublic("PACK_LANDING_BASE_URL", properties.getLandingBaseUrl(),
                "the 'sales closed' redirect and every checkout error send visitors there");
        warnIfNotPublic("PACK_THANKYOU_URL", properties.getThankyouUrl(), "it is the return address after paying");
        warnIfNotPublic("PACK_DOWNLOAD_BASE_URL", properties.getDownloadBaseUrl(), "it is the link in the access email");
    }

    private static void warnIfNotPublic(String variable, String value, String consequence) {
        if (!PackUrls.isPublicHttps(value)) {
            log.error("{} is \"{}\" (empty, localhost or not https) although sales are off: {}. "
                    + "Fix it before turning sales on.", variable, value, consequence);
        }
    }
}
