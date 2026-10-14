package com.tiam.pack.service;

import com.tiam.common.email.EmailAddresses;
import com.tiam.pack.config.PackProperties;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * "I lost my link": the public, unauthenticated way to get a pack access email again.
 *
 * <p>It must reveal NOTHING about who bought. The HTTP answer is the same 202 whatever happens, and so is
 * the time it takes: this method only counts the request against two quotas and hands the real work (look
 * up, rotate, send) to {@link PackAccessResender} on another thread, so a buyer's email and a stranger's
 * are indistinguishable by response or by latency. Requests over quota are dropped silently for the same
 * reason (3 per email per day and 10 per client IP per hour, both configurable).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackAccessResendService {

    private static final Pattern PLAUSIBLE_EMAIL = Pattern.compile("[^\\s@]{1,64}@[^\\s@]+\\.[^\\s@]{2,}");

    private final PackAccessResender resender;
    private final PackRateLimiter rateLimiter;
    private final PackProperties properties;

    public void request(String rawEmail, String clientIp) {
        String email = rawEmail == null ? "" : rawEmail.strip().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !PLAUSIBLE_EMAIL.matcher(email).matches()) {
            return;
        }
        PackProperties.RateLimits limits = properties.getRateLimits();
        boolean ipAllowed = rateLimiter.tryAcquire("resend-ip:" + clientIp, limits.getResendPerIp(), Duration.ofHours(1));
        boolean emailAllowed = rateLimiter.tryAcquire("resend-email:" + email, limits.getResendPerEmail(), Duration.ofDays(1));
        if (!ipAllowed || !emailAllowed) {
            log.warn("Pack access re-send over quota (ip={} email={}); dropped silently", clientIp, EmailAddresses.mask(email));
            return;
        }
        resender.resendAsync(email);
    }
}
