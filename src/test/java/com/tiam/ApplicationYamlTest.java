package com.tiam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards deploy-time settings that have no code path to test: they only exist in
 * application.yml, and a silent regression there would only show up in production.
 */
class ApplicationYamlTest {

    private static Properties yaml;

    @BeforeAll
    static void load() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        yaml = factory.getObject();
    }

    @Test
    void asyncExecutor_waitsForInFlightSendsOnShutdown() {
        // Without this, SIGTERM during a Render deploy interrupts an email mid-send.
        assertThat(yaml.getProperty("spring.task.execution.shutdown.await-termination")).isEqualTo("true");
        assertThat(yaml.getProperty("spring.task.execution.shutdown.await-termination-period")).isEqualTo("20s");
    }

    @Test
    void emailRetry_runsEveryFifteenMinutesOverTheLastSeventyTwoHours() {
        assertThat(yaml.getProperty("tiam.challenge.email-retry.interval-ms"))
                .isEqualTo("${CHALLENGE_EMAIL_RETRY_INTERVAL_MS:900000}");
        assertThat(yaml.getProperty("tiam.challenge.email-retry.enabled"))
                .isEqualTo("${CHALLENGE_EMAIL_RETRY_ENABLED:true}");
        assertThat(yaml.getProperty("tiam.challenge.email-retry.lookback-hours")).isEqualTo("72");
        assertThat(yaml.getProperty("tiam.challenge.email-retry.min-age-minutes")).isEqualTo("5");
    }

    @Test
    void emailCredentials_comeOnlyFromEnvironmentVariablesWithNoDefault() {
        assertThat(yaml.getProperty("tiam.email.resend-api-key")).isEqualTo("${RESEND_API_KEY:}");
        assertThat(yaml.getProperty("tiam.email.from")).isEqualTo("${EMAIL_FROM:}");
    }

    @Test
    void packSales_areOffUnlessTheOwnerTurnsThemOn() {
        assertThat(yaml.getProperty("tiam.pack.sales-enabled")).isEqualTo("${PACK_SALES_ENABLED:false}");
    }

    @Test
    void packPrices_defaultToTheLaunchPricesButComeFromTheEnvironment() {
        assertThat(yaml.getProperty("tiam.pack.price-ars")).isEqualTo("${PACK_PRICE_ARS:14990}");
        assertThat(yaml.getProperty("tiam.pack.games-price-ars")).isEqualTo("${PACK_GAMES_PRICE_ARS:10000}");
        assertThat(yaml.getProperty("tiam.pack.extra-price-ars")).isEqualTo("${PACK_EXTRA_PRICE_ARS:4990}");
    }

    @Test
    void packAccess_lastsNinetyDaysAndSignedUrlsFiveMinutes() {
        assertThat(yaml.getProperty("tiam.pack.access-days")).isEqualTo("${PACK_ACCESS_DAYS:90}");
        assertThat(yaml.getProperty("tiam.pack.downloads.url-ttl-seconds")).isEqualTo("300");
        assertThat(yaml.getProperty("tiam.pack.downloads.daily-cap")).isEqualTo("30");
        assertThat(yaml.getProperty("tiam.pack.downloads.total-cap")).isEqualTo("300");
    }

    @Test
    void packUrls_andNotificationAddresses_comeFromTheEnvironment() {
        assertThat(yaml.getProperty("tiam.pack.landing-base-url")).startsWith("${PACK_LANDING_BASE_URL:");
        assertThat(yaml.getProperty("tiam.pack.thankyou-url")).startsWith("${PACK_THANKYOU_URL:");
        assertThat(yaml.getProperty("tiam.pack.download-base-url")).startsWith("${PACK_DOWNLOAD_BASE_URL:");
        assertThat(yaml.getProperty("tiam.pack.regret-notify-to")).isEqualTo("${PACK_REGRET_NOTIFY_TO:}");
        assertThat(yaml.getProperty("tiam.pack.owner-notify-to")).isEqualTo("${PACK_OWNER_NOTIFY_TO:}");
    }

    @Test
    void packStorageCredentials_comeOnlyFromEnvironmentVariablesWithNoDefault() {
        assertThat(yaml.getProperty("tiam.pack.storage.r2-account-id")).isEqualTo("${R2_ACCOUNT_ID:}");
        assertThat(yaml.getProperty("tiam.pack.storage.r2-access-key-id")).isEqualTo("${R2_ACCESS_KEY_ID:}");
        assertThat(yaml.getProperty("tiam.pack.storage.r2-secret-access-key")).isEqualTo("${R2_SECRET_ACCESS_KEY:}");
        assertThat(yaml.getProperty("tiam.pack.storage.r2-bucket")).isEqualTo("${R2_BUCKET:}");
    }

    @Test
    void packReconciliationAndEmailRetry_runEveryFifteenMinutes() {
        assertThat(yaml.getProperty("tiam.pack.reconciliation.interval-ms"))
                .isEqualTo("${PACK_RECONCILIATION_INTERVAL_MS:900000}");
        assertThat(yaml.getProperty("tiam.pack.reconciliation.lookback-days")).isEqualTo("14");
        assertThat(yaml.getProperty("tiam.pack.reconciliation.failed-lookback-days")).isEqualTo("3");
        assertThat(yaml.getProperty("tiam.pack.reconciliation.max-per-run")).isEqualTo("100");
        assertThat(yaml.getProperty("tiam.pack.email-retry.interval-ms"))
                .isEqualTo("${PACK_EMAIL_RETRY_INTERVAL_MS:900000}");
        assertThat(yaml.getProperty("tiam.pack.email-retry.lookback-hours")).isEqualTo("72");
    }

    @Test
    void forwardedHeaders_areTrustedSoRateLimitsSeeTheRealClientIpBehindRender() {
        assertThat(yaml.getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    @Test
    void devMode_isOffByDefault_andNotListedInTheDeploymentBlueprint() throws Exception {
        assertThat(yaml.getProperty("tiam.pack.dev-mode")).isEqualTo("${PACK_DEV_MODE:false}");
        String blueprint = java.nio.file.Files.readString(java.nio.file.Path.of("render.yaml"));
        assertThat(blueprint).doesNotContain("PACK_DEV_MODE");
    }

    @Test
    void clientIp_isConfigurableFromTheEnvironment_andSafeByDefault() {
        assertThat(yaml.getProperty("tiam.net.client-ip.header")).isEqualTo("${CLIENT_IP_HEADER:}");
        assertThat(yaml.getProperty("tiam.net.client-ip.trusted-proxy-hops")).isEqualTo("${CLIENT_IP_TRUSTED_HOPS:0}");
        assertThat(yaml.getProperty("tiam.net.client-ip.diagnostics")).isEqualTo("${CLIENT_IP_DIAGNOSTICS:false}");
    }

    @Test
    void rateLimits_areCoarsePerIp_becauseCarriersShareAddresses() {
        assertThat(Integer.parseInt(yaml.getProperty("tiam.pack.rate-limits.checkout-per-ip"))).isGreaterThanOrEqualTo(10);
        assertThat(Integer.parseInt(yaml.getProperty("tiam.pack.rate-limits.token-calls-per-ip-total"))).isGreaterThanOrEqualTo(300);
        assertThat(yaml.getProperty("tiam.pack.rate-limits.pending-per-email-and-ip")).isEqualTo("3");
    }
}
