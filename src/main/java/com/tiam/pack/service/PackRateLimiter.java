package com.tiam.pack.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window limiter for the public pack endpoints (checkout, token calls, access re-send,
 * regret form).
 *
 * <p><b>Bounded by construction.</b> Callers key it with user-controlled values (emails, tokens), so the number
 * of keys is capped ({@value #DEFAULT_MAX_KEYS}) and the table is a least-recently-used map: when it is full the
 * key that has gone longest without a request is dropped. Keys whose window has fully elapsed are also purged by
 * time (every {@value #PURGE_INTERVAL_MS} ms), so a quiet instance holds almost nothing. Each key keeps at most
 * {@code limit} timestamps. The cost of the cap is deliberate and one-sided: under a flood of distinct keys a
 * stale offender can be forgotten early (a limit is lifted), but memory never grows and a key that is still being
 * hit is never the one evicted. Callers evaluate the client IP first and only then the user-controlled keys, so
 * flooding the table takes many distinct IPs.
 *
 * <p>Single-instance assumption, the same one the rest of the backend makes: with several instances each would
 * count on its own. It is also only as good as the client IP it is keyed on (see {@code ClientIpResolver}); an
 * unknown IP is not limited at all ({@link #tryAcquireByIp}), because blocking by an address we could not read
 * would block genuine buyers.
 *
 * <p>A blocked attempt is NOT recorded, so hammering an endpoint never extends the block.
 */
@Slf4j
@Component
public class PackRateLimiter {

    public static final int DEFAULT_MAX_KEYS = 50_000;
    static final long PURGE_INTERVAL_MS = 30_000L;
    /** Keys longer than this are replaced by their SHA-256, so no single key can be large. */
    private static final int MAX_KEY_LENGTH = 128;

    private final Clock clock;
    private final int maxKeys;
    private final Object lock = new Object();
    /** Access-ordered: the eldest entry is the least recently used one. Guarded by {@link #lock}. */
    private final LinkedHashMap<String, Window> windows = new LinkedHashMap<>(256, 0.75f, true);
    private long lastPurgeMillis;
    private long evictedSinceLastWarning;
    private long lastEvictionWarningMillis;

    public PackRateLimiter() {
        this(Clock.systemUTC(), DEFAULT_MAX_KEYS);
    }

    PackRateLimiter(Clock clock) {
        this(clock, DEFAULT_MAX_KEYS);
    }

    PackRateLimiter(Clock clock, int maxKeys) {
        this.clock = clock;
        this.maxKeys = Math.max(1, maxKeys);
    }

    /** Records one hit for {@code key} and returns true if it is within {@code limit} hits per {@code window}. */
    public boolean tryAcquire(String key, int limit, Duration window) {
        if (limit <= 0) {
            return false;
        }
        long now = clock.millis();
        long windowMillis = window.toMillis();
        String bounded = bound(key);

        synchronized (lock) {
            purgeExpiredIfDue(now);
            Window w = windows.get(bounded);
            boolean created = w == null;
            if (created) {
                w = new Window();
            }
            w.dropHitsOlderThan(now - windowMillis);
            if (w.size >= limit) {
                return false;
            }
            w.add(now, limit);
            w.expiresAtMillis = Math.max(w.expiresAtMillis, now + windowMillis);
            if (created) {
                windows.put(bounded, w);
                evictOverflow(now);
            }
            return true;
        }
    }

    /**
     * Like {@link #tryAcquire} for a key made of a scope and a client IP, but an unknown IP (null or blank, see
     * {@code ClientIpResolver}) is allowed and recorded nowhere: failing closed would punish genuine buyers for
     * our own inability to read their address.
     */
    public boolean tryAcquireByIp(String scope, String clientIp, int limit, Duration window) {
        if (clientIp == null || clientIp.isBlank()) {
            return true;
        }
        return tryAcquire(scope + ":" + clientIp, limit, window);
    }

    /** Read-only twin of {@link #tryAcquire}: would one more hit be allowed right now? Records nothing. */
    public boolean isWithinLimit(String key, int limit, Duration window) {
        if (limit <= 0) {
            return false;
        }
        long now = clock.millis();
        synchronized (lock) {
            Window w = windows.get(bound(key));
            if (w == null) {
                return true;
            }
            w.dropHitsOlderThan(now - window.toMillis());
            return w.size < limit;
        }
    }

    int trackedKeys() {
        synchronized (lock) {
            return windows.size();
        }
    }

    private void purgeExpiredIfDue(long now) {
        if (now - lastPurgeMillis < PURGE_INTERVAL_MS) {
            return;
        }
        lastPurgeMillis = now;
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMillis <= now) {
                it.remove();
            }
        }
    }

    private void evictOverflow(long now) {
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (windows.size() > maxKeys && it.hasNext()) {
            it.next();
            it.remove();
            evictedSinceLastWarning++;
        }
        if (evictedSinceLastWarning > 0 && now - lastEvictionWarningMillis >= 60_000L) {
            log.warn("Pack rate limiter is full ({} keys): {} least-recently-used entries were dropped. "
                    + "This is normal under a flood of distinct emails/IPs; it is bounded memory, not a leak.",
                    maxKeys, evictedSinceLastWarning);
            evictedSinceLastWarning = 0;
            lastEvictionWarningMillis = now;
        }
    }

    private static String bound(String key) {
        if (key.length() <= MAX_KEY_LENGTH) {
            return key;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return key.substring(0, key.indexOf(':') >= 0 && key.indexOf(':') < 32 ? key.indexOf(':') + 1 : 0)
                    + "#" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory on every JVM
        }
    }

    /** The recent hit times of one key, as a growable ring that never holds more than the limit asks for. */
    private static final class Window {
        private long[] times = new long[4];
        private int head;
        int size;
        long expiresAtMillis;

        void dropHitsOlderThan(long cutoff) {
            while (size > 0 && times[head] <= cutoff) {
                head = (head + 1) % times.length;
                size--;
            }
        }

        void add(long now, int limit) {
            if (size == times.length) {
                long[] grown = new long[Math.min(Math.max(times.length * 2, size + 1), Math.max(limit, size + 1))];
                for (int i = 0; i < size; i++) {
                    grown[i] = times[(head + i) % times.length];
                }
                times = grown;
                head = 0;
            }
            times[(head + size) % times.length] = now;
            size++;
        }
    }
}
