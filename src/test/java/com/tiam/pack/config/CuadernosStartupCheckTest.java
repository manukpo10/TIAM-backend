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
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The boot-time warnings, extended to the sales switch of the «Cuadernos TIAM» product. */
class CuadernosStartupCheckTest {

    PackProperties properties;
    PlansProperties plans;
    MercadoPagoProperties mercadoPago;
    PrivateFileStore store;
    EmailSender email;
    MockEnvironment environment;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.getCuadernos().setSalesEnabled(true); // the Pack stays off
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

    // --- both switches off --------------------------------------------------------------------------

    @Test
    void bothOffOnADeveloperMachine_saysNothingAtAll() {
        properties.getCuadernos().setSalesEnabled(false);
        properties.setThankyouUrl("http://localhost:5173/pack/gracias");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.events()).isEmpty();
        }
    }

    @Test
    void bothOffInProductionWithAWrongLandingUrl_stillFailsLoudly_becauseTheClosedRedirectGoesThere() {
        properties.getCuadernos().setSalesEnabled(false);
        properties.setLandingBaseUrl("http://localhost:5173");
        environment.setActiveProfiles("prod");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("PACK_LANDING_BASE_URL") && m.contains("sales are off"));
        }
    }

    // --- the Cuadernos on, the Pack off ----------------------------------------------------------------

    @Test
    void cuadernosOnWithEverythingInPlace_staysQuiet_andSaysWhichProductIsForSale() {
        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).isEmpty();
            assertThat(logs.messages(Level.WARN)).isEmpty();
            assertThat(logs.messages(Level.INFO)).anyMatch(m -> m.contains("Cuadernos sales are ENABLED")
                    && m.contains("CUADERNOS_SALES_ENABLED=true"));
            assertThat(logs.messages(Level.INFO)).noneMatch(m -> m.contains("Pack sales are ENABLED"));
        }
    }

    @Test
    void cuadernosOnWithTheLocalFileStore_failsLoudly_namingTheCuadernosSwitch() {
        when(store.isRemote()).thenReturn(false);

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("CUADERNOS_SALES_ENABLED=true")
                    && m.contains("R2") && m.contains("refused as closed"));
            assertThat(logs.messages(Level.ERROR)).noneMatch(m -> m.contains("PACK_SALES_ENABLED"));
        }
    }

    @Test
    void cuadernosOnWithoutRealEmail_failsLoudly() {
        when(email.isEnabled()).thenReturn(false);

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("RESEND_API_KEY"));
        }
    }

    @Test
    void cuadernosOnWithLocalhostUrls_failsLoudly() {
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
    void cuadernosOnWithoutAnyoneToNotify_failsLoudly() {
        properties.setOwnerNotifyTo("");
        properties.setRegretNotifyTo("");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("PACK_OWNER_NOTIFY_TO"));
        }
    }

    @Test
    void cuadernosOnWithoutMercadoPagoSettings_saysWhichSwitchIsAffected() {
        mercadoPago.setAccessToken("");
        mercadoPago.setWebhookSecret("");
        plans.setNotificationUrl("");

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("CUADERNOS_SALES_ENABLED=true")
                    && m.contains("MP_ACCESS_TOKEN"));
            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("CUADERNOS_SALES_ENABLED=true")
                    && m.contains("MP_NOTIFICATION_URL"));
            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("CUADERNOS_SALES_ENABLED=true")
                    && m.contains("MP_WEBHOOK_SECRET"));
        }
    }

    @Test
    void cuadernosOnWithAnInvalidPrice_failsLoudly_becauseEveryCheckoutWouldFail() {
        properties.getCuadernos().setPriceArs(BigDecimal.ZERO);
        properties.getCuadernos().setGamesPriceArs(new BigDecimal("-5"));

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("CUADERNOS_PRICE_ARS"));
            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.contains("CUADERNOS_GAMES_PRICE_ARS"));
        }
    }

    @Test
    void theCuadernosPrices_areNotCheckedWhileTheyAreNotForSale() {
        properties.getCuadernos().setSalesEnabled(false);
        properties.setSalesEnabled(true);
        properties.getCuadernos().setPriceArs(BigDecimal.ZERO);

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.ERROR)).noneMatch(m -> m.contains("CUADERNOS"));
        }
    }

    @Test
    void devMode_warnsLoudlyForTheCuadernosToo() {
        when(store.isRemote()).thenReturn(false);
        properties.setDevMode(true);

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.WARN)).anyMatch(m -> m.contains("PACK_DEV_MODE") && m.contains("DEVELOPMENT ONLY"));
            assertThat(logs.messages(Level.ERROR)).isEmpty();
        }
    }

    // --- the Pack on, the Cuadernos off: nothing changes ----------------------------------------------------

    @Test
    void packOnly_keepsItsExactMessages_andNeverMentionsTheCuadernos() {
        properties.getCuadernos().setSalesEnabled(false);
        properties.setSalesEnabled(true);
        when(store.isRemote()).thenReturn(false);

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.INFO)).anyMatch(m -> m.contains("Pack sales are ENABLED (PACK_SALES_ENABLED=true)"));
            assertThat(logs.messages(Level.ERROR)).anyMatch(m -> m.startsWith("PACK_SALES_ENABLED=true but the shop is NOT ready"));
            assertThat(logs.messages(Level.ERROR)).noneMatch(m -> m.contains("CUADERNOS"));
            assertThat(logs.messages(Level.INFO)).noneMatch(m -> m.contains("Cuadernos"));
        }
    }

    // --- both on ----------------------------------------------------------------------------------------------------

    @Test
    void bothOn_reportsBothProducts_andNamesBothSwitchesInTheProblems() {
        properties.setSalesEnabled(true);
        when(store.isRemote()).thenReturn(false);

        try (LogCapture logs = new LogCapture(PackStartupCheck.class)) {
            check().verify();

            assertThat(logs.messages(Level.INFO)).anyMatch(m -> m.contains("Pack sales are ENABLED"));
            assertThat(logs.messages(Level.INFO)).anyMatch(m -> m.contains("Cuadernos sales are ENABLED"));
            assertThat(logs.messages(Level.ERROR)).anyMatch(m ->
                    m.contains("PACK_SALES_ENABLED=true and CUADERNOS_SALES_ENABLED=true") && m.contains("NOT ready"));
        }
    }

    private PackStartupCheck check() {
        return new PackStartupCheck(properties, plans, mercadoPago,
                new PackSalesReadiness(properties, store, email, environment));
    }
}
