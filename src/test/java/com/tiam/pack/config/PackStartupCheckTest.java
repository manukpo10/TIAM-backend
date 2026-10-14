package com.tiam.pack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.storage.PrivateFileStore;
import com.tiam.subscription.config.MercadoPagoProperties;
import com.tiam.subscription.config.PlansProperties;
import com.tiam.testsupport.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PackStartupCheckTest {

    PackProperties properties;
    PlansProperties plans;
    MercadoPagoProperties mercadoPago;
    PrivateFileStore store;
    EmailSender email;
    MockEnvironment environment;

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
        store = mock(PrivateFileStore.class);
        when(store.isRemote()).thenReturn(true);
        email = mock(EmailSender.class);
        when(email.isEnabled()).thenReturn(true);
        environment = new MockEnvironment();
    }

    // --- sales off ---------------------------------------------------------------------------

    @Test
    void salesOffOnADeveloperMachine_saysNothingAtAll() {
        properties.setSalesEnabled(false);
        properties.setThankyouUrl("http://localhost:5173/pack/gracias");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.events()).isEmpty();
        }
    }

    @Test
    void salesOffInProductionWithGoodUrls_saysNothing() {
        properties.setSalesEnabled(false);
        environment.setActiveProfiles("prod");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.events()).isEmpty();
        }
    }

    @Test
    void salesOffInProductionWithAWrongLandingUrl_stillFailsLoudly_becauseTheClosedRedirectGoesThere() {
        properties.setSalesEnabled(false);
        properties.setLandingBaseUrl("http://localhost:5173");
        environment.setActiveProfiles("prod");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("PACK_LANDING_BASE_URL") && m.contains("sales are off"));
        }
    }

    // --- sales on ----------------------------------------------------------------------------

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
            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("refused as closed"));
        }
    }

    @Test
    void salesOnWithTheLocalFileStore_failsLoudly() {
        when(store.isRemote()).thenReturn(false);

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("R2") && m.contains("refused as closed"));
        }
    }

    @Test
    void salesOnWithoutRealEmail_failsLoudly() {
        when(email.isEnabled()).thenReturn(false);

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("RESEND_API_KEY"));
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
    void salesOnWithoutAnyoneToNotify_isNowAnErrorBecauseTheCheckoutRefusesToOpen() {
        properties.setOwnerNotifyTo("");
        properties.setRegretNotifyTo("");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("PACK_OWNER_NOTIFY_TO"));
        }
    }

    // --- dev mode ----------------------------------------------------------------------------

    @Test
    void devMode_warnsLoudlyInsteadOfListingProblems() {
        when(store.isRemote()).thenReturn(false);
        properties.setDevMode(true);

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.WARN)).anyMatch(m -> m.contains("PACK_DEV_MODE") && m.contains("DEVELOPMENT ONLY"));
            assertThat(logs.messages(Level.ERROR)).isEmpty();
        }
    }

    @Test
    void devModeInProduction_isReportedAsIgnored() {
        properties.setDevMode(true);
        environment.setActiveProfiles("prod");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("PACK_DEV_MODE") && m.contains("IGNORED"));
        }
    }

    private PackStartupCheck check() {
        return new PackStartupCheck(properties, plans, mercadoPago,
                new PackSalesReadiness(properties, store, email, environment));
    }
}
