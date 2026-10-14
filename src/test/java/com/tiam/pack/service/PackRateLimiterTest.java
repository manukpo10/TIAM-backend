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

    @Test
    void expiredKeys_areDroppedByTime_evenWhenTheTableIsNowhereNearFull() {
        limiter.tryAcquire("short", 1, Duration.ofSeconds(10));
        limiter.tryAcquire("long", 1, Duration.ofDays(1));

        clock.advance(Duration.ofMinutes(5));
        limiter.tryAcquire("another", 1, Duration.ofSeconds(1)); // any call after the purge interval

        assertThat(limiter.trackedKeys()).isEqualTo(2); // "short" is gone; "long" and "another" remain
        assertThat(limiter.tryAcquire("long", 1, Duration.ofDays(1))).isFalse(); // still limited
    }

    @Test
    void thereIsAHardCapOnTheNumberOfKeys_evenWhenEveryKeyIsStillLive() {
        PackRateLimiter capped = new PackRateLimiter(clock, 1_000);

        for (int i = 0; i < 200_000; i++) {
            capped.tryAcquire("resend-email:user" + i + "@example.com", 3, Duration.ofDays(1));
        }

        assertThat(capped.trackedKeys()).isLessThanOrEqualTo(1_000);
    }

    @Test
    void whenTheCapEvicts_theLeastRecentlyUsedKeyGoesFirst_soAnActiveOffenderStaysLimited() {
        PackRateLimiter capped = new PackRateLimiter(clock, 100);
        capped.tryAcquire("offender", 1, Duration.ofDays(1)); // limited from now on
        for (int i = 0; i < 99; i++) {
            capped.tryAcquire("filler" + i, 1, Duration.ofDays(1));
            capped.tryAcquire("offender", 1, Duration.ofDays(1)); // keeps being hit, so it stays recent
        }

        for (int i = 0; i < 50; i++) {
            capped.tryAcquire("newcomer" + i, 1, Duration.ofDays(1));
        }

        assertThat(capped.tryAcquire("offender", 1, Duration.ofDays(1))).isFalse();
    }

    @Test
    void aVeryLongKeyIsBoundedButStillDistinguishesDifferentValues() {
        String longA = "regret-email:" + "a".repeat(250) + "@example.com";
        String longB = "regret-email:" + "a".repeat(250) + "@example.org";

        assertThat(limiter.tryAcquire(longA, 1, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire(longA, 1, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire(longB, 1, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void anUnknownIp_failsOpen_andRecordsNothing() {
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquireByIp("checkout-ip", null, 1, Duration.ofMinutes(10))).isTrue();
            assertThat(limiter.tryAcquireByIp("checkout-ip", "  ", 1, Duration.ofMinutes(10))).isTrue();
        }

        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void aKnownIp_isLimitedPerScope() {
        assertThat(limiter.tryAcquireByIp("checkout-ip", "1.2.3.4", 1, Duration.ofMinutes(10))).isTrue();
        assertThat(limiter.tryAcquireByIp("checkout-ip", "1.2.3.4", 1, Duration.ofMinutes(10))).isFalse();
        assertThat(limiter.tryAcquireByIp("resend-ip", "1.2.3.4", 1, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void aWindowIsOnlyAsBigAsItsLimit_soNoKeyHoldsMoreHitsThanItEverAllows() {
        for (int i = 0; i < 1_000; i++) {
            limiter.tryAcquire("k", 3, Duration.ofHours(1));
        }

        assertThat(limiter.tryAcquire("k", 3, Duration.ofHours(1))).isFalse();
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(limiter.tryAcquire("k", 3, Duration.ofHours(1))).isTrue();
    }
}
