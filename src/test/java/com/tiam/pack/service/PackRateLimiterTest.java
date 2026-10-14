package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tiam.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackRateLimiterTest {

    MutableClock clock;
    PackRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        limiter = new PackRateLimiter(clock);
    }

    @Test
    void allowsUpToTheLimit_thenBlocks() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("ip:1.1.1.1", 3, Duration.ofMinutes(10))).isTrue();
        }

        assertThat(limiter.tryAcquire("ip:1.1.1.1", 3, Duration.ofMinutes(10))).isFalse();
    }

    @Test
    void aBlockedAttempt_doesNotExtendTheBlock() {
        limiter.tryAcquire("k", 1, Duration.ofMinutes(10));
        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.tryAcquire("k", 1, Duration.ofMinutes(10))).isFalse();

        clock.advance(Duration.ofMinutes(5).plusSeconds(1)); // 10 min after the FIRST hit

        assertThat(limiter.tryAcquire("k", 1, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void theWindowSlides_soOldHitsStopCounting() {
        limiter.tryAcquire("k", 2, Duration.ofMinutes(1));
        clock.advance(Duration.ofSeconds(40));
        limiter.tryAcquire("k", 2, Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("k", 2, Duration.ofMinutes(1))).isFalse();

        clock.advance(Duration.ofSeconds(30)); // the first hit is now 70s old

        assertThat(limiter.tryAcquire("k", 2, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void keysAreIndependent() {
        limiter.tryAcquire("a", 1, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("a", 1, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire("b", 1, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void aNonPositiveLimitBlocksEverything() {
        assertThat(limiter.tryAcquire("k", 0, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire("k", -5, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void manyDistinctKeys_doNotGrowWithoutBound() {
        for (int i = 0; i < 25_000; i++) {
            limiter.tryAcquire("ip:" + i, 1, Duration.ofSeconds(1));
        }
        clock.advance(Duration.ofHours(25));

        limiter.tryAcquire("trigger", 1, Duration.ofSeconds(1));

        assertThat(limiter.trackedKeys()).isLessThan(100);
    }
}
