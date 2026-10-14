package com.tiam.pack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tiam.common.email.EmailSender;
import com.tiam.pack.storage.PrivateFileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The fail-closed rule: with sales ON, a checkout may only start when a buyer who pays will really receive
 * what they paid for. A paid order must never point at localhost, depend on a no-op email, or serve files from
 * a development folder.
 */
class PackSalesReadinessTest {

    PackProperties properties;
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
        store = mock(PrivateFileStore.class);
        when(store.isRemote()).thenReturn(true);
        email = mock(EmailSender.class);
        when(email.isEnabled()).thenReturn(true);
        environment = new MockEnvironment();
    }

    private PackSalesReadiness readiness() {
        return new PackSalesReadiness(properties, store, email, environment);
    }

    @Test
    void everythingInPlace_isReady() {
        assertThat(readiness().problems()).isEmpty();
        assertThat(readiness().isReady()).isTrue();
    }

    @Test
    void aLocalFolderStore_isNotReady_becauseBuyersWouldGetAnAddressOnTheirOwnComputer() {
        when(store.isRemote()).thenReturn(false);

        assertThat(readiness().problems()).anyMatch(p -> p.contains("R2"));
        assertThat(readiness().isReady()).isFalse();
    }

    @Test
    void aNoOpEmailSender_isNotReady_becauseTheAccessEmailWouldNeverLeave() {
        when(email.isEnabled()).thenReturn(false);

        assertThat(readiness().problems()).anyMatch(p -> p.contains("RESEND_API_KEY") && p.contains("EMAIL_FROM"));
    }

    @Test
    void everyBaseUrlMustBePublicHttps() {
        properties.setLandingBaseUrl("http://localhost:5173");
        properties.setThankyouUrl("http://tiamdigital.com/pack/gracias");
        properties.setDownloadBaseUrl("");

        assertThat(readiness().problems())
                .anyMatch(p -> p.contains("PACK_LANDING_BASE_URL"))
                .anyMatch(p -> p.contains("PACK_THANKYOU_URL"))
                .anyMatch(p -> p.contains("PACK_DOWNLOAD_BASE_URL"));
    }

    @Test
    void someoneMustBeAbleToHearAboutAPaymentThatNeedsAHuman() {
        properties.setOwnerNotifyTo("");
        properties.setRegretNotifyTo("");

        assertThat(readiness().problems()).anyMatch(p -> p.contains("PACK_OWNER_NOTIFY_TO"));
    }

    @Test
    void theRegretAddressAloneIsEnoughForTheOwnerAlerts() {
        properties.setOwnerNotifyTo("");
        properties.setRegretNotifyTo("owner@example.com");

        assertThat(readiness().isReady()).isTrue();
    }

    @Test
    void devMode_allowsTheLocalSetup_whenNotInProduction() {
        when(store.isRemote()).thenReturn(false);
        when(email.isEnabled()).thenReturn(false);
        properties.setThankyouUrl("http://localhost:5173/pack/gracias");
        properties.setOwnerNotifyTo("");
        properties.setDevMode(true);

        assertThat(readiness().isReady()).isTrue();
        assertThat(readiness().devModeActive()).isTrue();
    }

    @Test
    void devMode_isIgnoredInTheProdProfile_soAForgottenFlagCannotOpenARealShop() {
        when(store.isRemote()).thenReturn(false);
        properties.setDevMode(true);
        environment.setActiveProfiles("prod");

        assertThat(readiness().devModeActive()).isFalse();
        assertThat(readiness().devModeIgnored()).isTrue();
        assertThat(readiness().isReady()).isFalse();
    }

    @Test
    void devModeOff_isTheDefault() {
        assertThat(new PackProperties().isDevMode()).isFalse();
    }
}
