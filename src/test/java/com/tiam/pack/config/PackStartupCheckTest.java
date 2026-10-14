package com.tiam.pack.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.tiam.subscription.config.MercadoPagoProperties;
import com.tiam.subscription.config.PlansProperties;
import com.tiam.testsupport.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackStartupCheckTest {

    PackProperties properties;
    PlansProperties plans;
    MercadoPagoProperties mercadoPago;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.setSalesEnabled(true);
        properties.setLandingBaseUrl("https://tiamdigital.com");
        properties.setThankyouUrl("https://tiamdigital.com/pack/gracias");
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setOwnerNotifyTo("owner@example.com");
        plans = new PlansProperties();
        plans.setNotificationUrl("https://api.tiamdigital.com/webhooks/mercadopago");
        mercadoPago = new MercadoPagoProperties();
        mercadoPago.setAccessToken("configured");
        mercadoPago.setWebhookSecret("configured");
    }

    @Test
    void salesOff_saysNothingAtAll() {
        properties.setSalesEnabled(false);
        properties.setThankyouUrl("http://localhost:5173/pack/gracias");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.events()).isEmpty();
        }
    }

    @Test
    void salesOnWithEverythingInPlace_staysQuiet() {
        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).isEmpty();
            assertThat(logs.messages(Level.WARN)).isEmpty();
        }
    }

    @Test
    void salesOnWithLocalhostUrls_failsLoudly_becauseMercadoPagoAndBuyersCannotUseThem() {
        properties.setThankyouUrl("http://localhost:5173/pack/gracias");
        properties.setDownloadBaseUrl("http://localhost:5173/pack/descargas");
        properties.setLandingBaseUrl("http://localhost:5173");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("PACK_THANKYOU_URL"));
            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("PACK_DOWNLOAD_BASE_URL"));
            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("PACK_LANDING_BASE_URL"));
        }
    }

    @Test
    void salesOnWithoutAWebhookUrl_warnsThatPaymentsWillNotNotify() {
        plans.setNotificationUrl("");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("MP_NOTIFICATION_URL"));
        }
    }

    @Test
    void salesOnWithoutAWebhookSecret_warns() {
        mercadoPago.setWebhookSecret("");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("MP_WEBHOOK_SECRET"));
        }
    }

    @Test
    void salesOnWithoutMercadoPagoCredentials_saysSo() {
        mercadoPago.setAccessToken("");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("MP_ACCESS_TOKEN"));
        }
    }

    @Test
    void salesOnWithoutAnyoneToNotify_warnsThatAlertsWillOnlyBeLogged() {
        properties.setOwnerNotifyTo("");
        properties.setRegretNotifyTo("");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.WARN)).anyMatch(m -> m.contains("PACK_OWNER_NOTIFY_TO"));
            assertThat(logs.messages(Level.ERROR)).isEmpty();
        }
    }

    private PackStartupCheck check() {
        return new PackStartupCheck(properties, plans, mercadoPago);
    }
}
