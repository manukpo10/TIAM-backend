package com.tiam.subscription.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.tiam.testsupport.LogCapture;
import org.junit.jupiter.api.Test;

class MercadoPagoWebhookSecretCheckTest {

    private static MercadoPagoWebhookSecretCheck checkWithSecret(String secret) {
        MercadoPagoProperties properties = new MercadoPagoProperties();
        properties.setWebhookSecret(secret);
        return new MercadoPagoWebhookSecretCheck(properties);
    }

    @Test
    void secretUnset_logsAClearWarnAtStartup() {
        for (String unset : new String[] {null, "", "   "}) {
            try (LogCapture logs = new LogCapture(MercadoPagoWebhookSecretCheck.class)) {
                checkWithSecret(unset).warnIfWebhookSecretMissing();

                assertThat(logs.messages(Level.WARN)).singleElement().satisfies(line -> assertThat(line)
                        .contains("MP_WEBHOOK_SECRET")
                        .contains("DISABLED")
                        .contains("/webhooks/mercadopago"));
            }
        }
    }

    @Test
    void secretSet_logsNothingAndNeverPrintsTheSecret() {
        try (LogCapture logs = new LogCapture(MercadoPagoWebhookSecretCheck.class)) {
            checkWithSecret("s3cr3t-value").warnIfWebhookSecretMissing();

            assertThat(logs.events()).isEmpty();
        }
    }
}
