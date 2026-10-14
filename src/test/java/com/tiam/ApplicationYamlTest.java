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
}
