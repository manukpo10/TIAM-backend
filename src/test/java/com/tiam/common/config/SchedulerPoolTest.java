package com.tiam.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The Desafío jobs (purchase reconciliation, email retry) and the pack jobs share the application's task
 * scheduler. With Spring's default of ONE thread, a pack reconciliation waiting on a slow Mercado Pago held the
 * only thread and the Desafío's jobs did not run, exactly when Mercado Pago was struggling. This loads the real
 * application.yml and shows the scheduler it produces has more than one thread, and that two long-running tasks
 * genuinely overlap.
 */
class SchedulerPoolTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void theApplicationScheduler_hasMoreThanOneThread() {
        runner.run(context -> {
            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);

            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThan(1);
        });
    }

    @Test
    void aTaskStuckOnASlowDependency_doesNotStopAnotherOneFromRunning() {
        runner.run(context -> {
            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch otherJobRan = new CountDownLatch(1);

            scheduler.execute(() -> await(release)); // "the pack reconciliation waiting on Mercado Pago"
            scheduler.execute(otherJobRan::countDown); // "the Desafío reconciliation"

            assertThat(otherJobRan.await(5, TimeUnit.SECONDS)).as("the second job ran while the first was stuck").isTrue();
            release.countDown();
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
