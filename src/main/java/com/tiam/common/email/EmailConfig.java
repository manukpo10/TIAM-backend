package com.tiam.common.email;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Picks the {@link EmailSender}: Resend when both {@code RESEND_API_KEY} and
 * {@code EMAIL_FROM} are set, otherwise the logging fallback so the app still
 * boots and runs (this is what keeps production safe until the owner configures it).
 *
 * <p>Chosen here instead of with {@code @ConditionalOnProperty} because the
 * placeholders resolve to an empty string when the env var is unset, and
 * {@code @ConditionalOnProperty} treats an empty string as "present".
 *
 * <p>Both values are trimmed: a trailing newline pasted into a hosting dashboard
 * would otherwise make every request fail with an exception that echoes the header.
 * A key that still holds whitespace or control characters after trimming can never
 * be valid, so it is treated as not configured instead of failing on every send.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(EmailProperties.class)
public class EmailConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public EmailSender emailSender(EmailProperties properties) {
        return build(properties, EmailConfig::restClientWithTimeouts);
    }

    /** Package-private so tests can inject a RestClient bound to a mock server. */
    EmailSender build(EmailProperties properties, Supplier<RestClient> restClients) {
        String apiKey = trimmed(properties.getResendApiKey());
        String from = trimmed(properties.getFrom());

        if (!apiKey.isEmpty() && hasWhitespaceOrControlCharacters(apiKey)) {
            log.warn("Email delivery DISABLED: RESEND_API_KEY contains spaces or control characters "
                    + "(bad copy/paste?) — emails will only be logged. Re-enter the key without them.");
            return new LoggingEmailSender();
        }

        if (!apiKey.isEmpty() && !from.isEmpty()) {
            Optional<String> replyTo = resolveReplyTo(properties);
            log.info("Email delivery: Resend enabled (from={}, reply-to={})", from,
                    replyTo.map(EmailAddresses::mask).orElse("none: replies would go to the sender address"));
            return new ResendEmailSender(restClients.get(), apiKey, from, replyTo.orElse(null));
        }

        if (!apiKey.isEmpty()) {
            log.warn("Email delivery DISABLED: RESEND_API_KEY is set but EMAIL_FROM is not — "
                    + "emails will only be logged. Set EMAIL_FROM (e.g. \"TIAM <hola@tiamdigital.com>\").");
        } else {
            log.warn("Email delivery DISABLED: RESEND_API_KEY is not set — "
                    + "emails (e.g. the Desafío access link after payment) will only be logged, not sent.");
        }
        return new LoggingEmailSender();
    }

    /**
     * The Reply-To for every email: {@code EMAIL_REPLY_TO}, else the owner's address ({@code PACK_OWNER_NOTIFY_TO}), else
     * none. A value that is set but is not a plain email address is logged (never echoed: it could hold anything) and
     * skipped, so it can neither break the sending nor inject a header.
     */
    static Optional<String> resolveReplyTo(EmailProperties properties) {
        Optional<String> explicit = usableReplyTo("EMAIL_REPLY_TO", properties.getReplyTo());
        return explicit.isPresent() ? explicit : usableReplyTo("PACK_OWNER_NOTIFY_TO", properties.getReplyToFallback());
    }

    private static Optional<String> usableReplyTo(String variable, String raw) {
        if (trimmed(raw).isEmpty()) {
            return Optional.empty();
        }
        Optional<String> plain = EmailAddresses.plainAddress(raw);
        if (plain.isEmpty()) {
            log.warn("{} is set but is not a plain email address (one address such as owner@gmail.com, no name, no list): "
                    + "it is NOT used as the Reply-To.", variable);
        }
        return plain;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean hasWhitespaceOrControlCharacters(String value) {
        return value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    private static RestClient restClientWithTimeouts() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
