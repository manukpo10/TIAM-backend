package com.tiam.pack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackSalesReadinessProductTest.FakeStore;
import com.tiam.pack.domain.PackEdition;
import com.tiam.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * When the cached answer about R2 expires, ONE request refreshes it (up to ten sequential HEADs) and the rest keep using
 * the last known answer instead of piling onto R2. Only the very first check has no answer to fall back on: there the
 * others wait for that same single refresh. Each product refreshes on its own.
 */
class PackSalesReadinessConcurrencyTest {

    PackProperties properties;
    FakeStore store;
    MutableClock clock;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        store = new FakeStore();
        clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        for (PackEdition edition : PackEdition.values()) {
            store.upload(edition, "v1");
        }
        pool = Executors.newFixedThreadPool(16);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void whenTheAnswerExpires_onlyOneRefreshRuns_andTheOthersUseTheLastKnownAnswer() throws Exception {
        PackSalesReadiness readiness = readiness();
        assertThat(readiness.productProblems(PackEdition.CUADERNOS)).isEmpty(); // fills the cache: complete
        clock.advance(Duration.ofMinutes(11));                                   // ... and now it has expired
        CountDownLatch insideR2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        store.beforeAnswer = keys -> {
            insideR2.countDown();
            await(release);
        };

        Future<List<String>> refresher = pool.submit(() -> readiness.productProblems(PackEdition.CUADERNOS));
        assertThat(insideR2.await(5, TimeUnit.SECONDS)).as("the refresher is waiting on a slow R2").isTrue();
        List<Future<List<String>>> others = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            others.add(pool.submit(() -> readiness.productProblems(PackEdition.CUADERNOS)));
        }

        for (Future<List<String>> other : others) {
            assertThat(other.get(2, TimeUnit.SECONDS)).as("served from the last known answer, without waiting").isEmpty();
        }
        assertThat(store.asked).as("one initial check + the one refresh in flight").isEqualTo(2);

        release.countDown();
        assertThat(refresher.get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(store.asked).isEqualTo(2);
    }

    @Test
    void theLastKnownAnswerIsAGap_stayClosedWhileTheRefreshRuns() throws Exception {
        store.remove(PackEdition.CUADERNOS, "v1", "cuaderno-1-a4");
        PackSalesReadiness readiness = readiness();
        assertThat(readiness.productProblems(PackEdition.CUADERNOS)).hasSize(1);
        clock.advance(Duration.ofSeconds(31));
        CountDownLatch insideR2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        store.beforeAnswer = keys -> {
            insideR2.countDown();
            await(release);
        };

        Future<List<String>> refresher = pool.submit(() -> readiness.productProblems(PackEdition.CUADERNOS));
        assertThat(insideR2.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(pool.submit(() -> readiness.productProblems(PackEdition.CUADERNOS)).get(2, TimeUnit.SECONDS))
                .as("a gap is not forgotten just because a refresh is running").hasSize(1);

        release.countDown();
        refresher.get(5, TimeUnit.SECONDS);
    }

    @Test
    void theVeryFirstCheck_hasNothingToFallBackOn_soTheOthersWaitForTheSameSingleRefresh() throws Exception {
        store.remove(PackEdition.CUADERNOS, "v1", "cuaderno-2-carta");
        PackSalesReadiness readiness = readiness();
        CountDownLatch insideR2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        store.beforeAnswer = keys -> {
            insideR2.countDown();
            await(release);
        };
        int callers = 6;
        CountDownLatch started = new CountDownLatch(callers);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            futures.add(pool.submit(() -> {
                started.countDown();
                return readiness.productProblems(PackEdition.CUADERNOS);
            }));
        }
        assertThat(insideR2.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300); // give the late callers time to reach the wait

        assertThat(futures).as("nobody answers before R2 does").noneMatch(Future::isDone);
        release.countDown();

        for (Future<List<String>> future : futures) {
            List<String> problems = future.get(5, TimeUnit.SECONDS);
            assertThat(problems).hasSize(1);
            assertThat(problems.get(0)).contains("cuaderno-2.pdf");
        }
        assertThat(store.asked).as("a single refresh served everyone").isEqualTo(1);
    }

    @Test
    void eachProductRefreshesOnItsOwn_aSlowOneDoesNotBlockTheOther() throws Exception {
        PackSalesReadiness readiness = readiness();
        readiness.productProblems(PackEdition.CUADERNOS);
        readiness.productProblems(PackEdition.FAMILIAS);
        clock.advance(Duration.ofMinutes(11));
        CountDownLatch insideR2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        store.beforeAnswer = keys -> {
            if (keys.stream().anyMatch(key -> key.contains("/cuadernos/"))) {
                insideR2.countDown();
                await(release);
            }
        };

        Future<List<String>> slow = pool.submit(() -> readiness.productProblems(PackEdition.CUADERNOS));
        assertThat(insideR2.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(pool.submit(() -> readiness.productProblems(PackEdition.FAMILIAS)).get(2, TimeUnit.SECONDS)).isEmpty();
        assertThat(store.asked).as("the Pack was refreshed while the Cuadernos' refresh was stuck").isEqualTo(4);

        release.countDown();
        slow.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aRefreshThatBlowsUp_releasesTheLock_soTheNextExpiryCanRefreshAgain() throws Exception {
        PackSalesReadiness readiness = readiness();
        readiness.productProblems(PackEdition.CUADERNOS);
        clock.advance(Duration.ofMinutes(11));
        store.failure = new IllegalStateException("boom");

        assertThat(pool.submit(() -> readiness.productProblems(PackEdition.CUADERNOS)).get(5, TimeUnit.SECONDS)).hasSize(1);

        store.failure = null;
        clock.advance(Duration.ofSeconds(31));
        assertThat(pool.submit(() -> readiness.productProblems(PackEdition.CUADERNOS)).get(5, TimeUnit.SECONDS)).isEmpty();
    }

    private PackSalesReadiness readiness() {
        EmailSender email = mock(EmailSender.class);
        when(email.isEnabled()).thenReturn(true);
        return new PackSalesReadiness(properties, store, email, new MockEnvironment(), clock);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
