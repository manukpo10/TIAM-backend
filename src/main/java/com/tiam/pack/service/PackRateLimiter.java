package com.tiam.pack.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window limiter for the public pack endpoints (checkout, token calls, access
 * re-send, regret form).
 *
 * <p>Single-instance assumption, the same one the rest of the backend makes (per-phone purchase locks,
 * the reconciliation job): with several instances each would count on its own. It is also only as good
 * as the client IP it is keyed on, which is why {@code server.forward-headers-strategy} is set.
 *
 * <p>A blocked attempt is NOT recorded, so hammering the endpoint never extends the block.
 */
@Component
public class PackRateLimiter {

    /** Above this many tracked keys, stale ones are dropped on the next call. */
    private static final int CLEANUP_THRESHOLD = 10_000;
    /** The longest window anyone asks for is a day; anything idle longer than this is garbage. */
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private volatile long lastCleanupMillis;

    public PackRateLimiter() {
        this(Clock.systemUTC());
    }

    PackRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Records one hit for {@code key} and returns true if it is within {@code limit} hits per {@code window}. */
    public boolean tryAcquire(String key, int limit, Duration window) {
        if (limit <= 0) {
            return false;
        }
        long now = clock.millis();
        if (hits.size() > CLEANUP_THRESHOLD && now - lastCleanupMillis >= 60_000L) {
            lastCleanupMillis = now;
            dropStaleKeys(now);
        }
        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            long cutoff = now - window.toMillis();
            while (!deque.isEmpty() && deque.peekFirst() <= cutoff) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    int trackedKeys() {
        return hits.size();
    }

    private void dropStaleKeys(long now) {
        long cutoff = now - STALE_AFTER.toMillis();
        hits.entrySet().removeIf(entry -> {
            Deque<Long> deque = entry.getValue();
            synchronized (deque) {
                return deque.isEmpty() || deque.peekLast() <= cutoff;
            }
        });
    }
}
