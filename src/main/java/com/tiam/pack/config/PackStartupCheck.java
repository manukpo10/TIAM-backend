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
 * One loud block in the boot log when pack sales ({@code PACK_SALES_ENABLED}) or the Cuadernos sales
 * ({@code CUADERNOS_SALES_ENABLED}) are switched ON with a setting that would make a sale fail or a
 * buyer receive a dead link, listing exactly what the checkout will refuse until it is fixed (see
 * {@link PackSalesReadiness}). It changes no behaviour itself. With both off it stays quiet, except for the one
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
        boolean packOn = properties.isSalesEnabled();
        boolean cuadernosOn = properties.getCuadernos().isSalesEnabled();
        if (!packOn && !cuadernosOn) {
            checkUrlsWhileSalesAreOff();
            return;
        }
        // The shop (R2, email, URLs, Mercado Pago) is shared, so one block covers whichever products are for sale; the
        // switch that is on is named in every line so the owner knows which variable started it.
        String switches = packOn && cuadernosOn
                ? "PACK_SALES_ENABLED=true and CUADERNOS_SALES_ENABLED=true"
                : packOn ? "PACK_SALES_ENABLED=true" : "CUADERNOS_SALES_ENABLED=true";
        if (packOn) {
            log.info("Pack sales are ENABLED (PACK_SALES_ENABLED=true)");
        }
        if (cuadernosOn) {
            log.info("Cuadernos sales are ENABLED (CUADERNOS_SALES_ENABLED=true)");
            checkCuadernosPrices();
        }

        if (readiness.devModeActive()) {
            log.warn("PACK_DEV_MODE is ON: checkouts may start with the local file folder, the logging-only email "
                    + "sender and localhost URLs. DEVELOPMENT ONLY - a real buyer would get dead links.");
        } else {
            readiness.problems().forEach(problem -> log.error(
                    "{} but the shop is NOT ready, every checkout is refused as closed "
                            + "(?error=cerrado) until this is fixed: {}", switches, problem));
        }

        if (!StringUtils.hasText(mercadoPago.getAccessToken())) {
            log.error("{} but MP_ACCESS_TOKEN is not set: every checkout will be refused as closed.", switches);
        }
        if (!StringUtils.hasText(plans.getNotificationUrl())) {
            log.error("{} but MP_NOTIFICATION_URL is empty: Mercado Pago will not notify payments, "
                    + "so orders are only confirmed by the buyer's return page or the reconciliation job.", switches);
        }
        if (!StringUtils.hasText(mercadoPago.getWebhookSecret())) {
            log.error("{} but MP_WEBHOOK_SECRET is not set: payment notifications are not "
                    + "signature-checked. Set it before taking real payments.", switches);
        }
    }

    /** A zero or negative price makes every Cuadernos checkout fail with an internal error: say so at boot instead. */
    private void checkCuadernosPrices() {
        PackProperties.Cuadernos cuadernos = properties.getCuadernos();
        if (cuadernos.getPriceArs() == null || cuadernos.getPriceArs().signum() <= 0) {
            log.error("CUADERNOS_SALES_ENABLED=true but CUADERNOS_PRICE_ARS is \"{}\": it must be greater than zero, "
                    + "otherwise every Cuadernos checkout fails.", cuadernos.getPriceArs());
        }
        if (cuadernos.getGamesPriceArs() == null || cuadernos.getGamesPriceArs().signum() < 0) {
            log.error("CUADERNOS_SALES_ENABLED=true but CUADERNOS_GAMES_PRICE_ARS is \"{}\": it must be zero or more, "
                    + "otherwise every Cuadernos checkout with the Desafío fails.", cuadernos.getGamesPriceArs());
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
