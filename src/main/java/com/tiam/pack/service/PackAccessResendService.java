package com.tiam.pack.service;

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
 * the time it takes: this method only counts the request against the visitor's quota and hands the real work
 * (look up, issue, send) to {@link PackAccessResender} on another thread, so a buyer's email and a stranger's
 * are indistinguishable by response or by latency. Requests over quota are dropped silently for the same reason.
 *
 * <p>The per-email quota is NOT counted here: it is spent by the resender, only for an email that has a paid
 * order. Counting it here made every distinct email a stranger typed add an entry to the limiter, and let a
 * stranger use up a real buyer's daily quota with no buyer-visible trace.
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
        // An unknown IP is not limited (fail open); the per-email quota lives in the resender.
        if (!rateLimiter.tryAcquireByIp("resend-ip", clientIp, properties.getRateLimits().getResendPerIp(), Duration.ofHours(1))) {
            log.warn("Pack access re-send over the IP quota (ip={}); dropped silently", clientIp);
            return;
        }
        resender.resendAsync(email);
    }
}
