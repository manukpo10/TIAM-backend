package com.tiam.challenge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.challenge.domain.ChallengePurchasePaidEvent;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.common.email.EmailSender;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the Spring wiring that unit tests calling the methods directly cannot:
 * the listener fires only AFTER the publishing transaction commits, never when it
 * rolls back, and on a different thread than the one that published (so the
 * Mercado Pago webhook is not held up and no committed transaction is reused).
 * Runs in a bare Spring context with a no-op transaction manager — no database.
 */
class ChallengePurchaseEmailListenerWiringTest {

    private static final ChallengePurchaseRepository REPOSITORY = mock(ChallengePurchaseRepository.class);

    private AnnotationConfigApplicationContext context;
    private TransactionTemplate transactionTemplate;
    private final AtomicReference<String> listenerThread = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(REPOSITORY);
        when(REPOSITORY.findById(anyLong())).thenAnswer(invocation -> {
            listenerThread.set(Thread.currentThread().getName());
            return Optional.empty();
        });
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void committedTransaction_runsTheListenerAfterCommit_onAnotherThread() {
        String publisherThread = Thread.currentThread().getName();

        transactionTemplate.executeWithoutResult(status -> {
            context.publishEvent(new ChallengePurchasePaidEvent(42L));
            // Still inside the transaction: nothing may have happened yet.
            verifyNoInteractions(REPOSITORY);
        });

        verify(REPOSITORY, timeout(3000)).findById(42L);
        assertThat(listenerThread.get()).isNotNull().isNotEqualTo(publisherThread);
    }

    @Test
    void rolledBackTransaction_neverRunsTheListener() {
        transactionTemplate.executeWithoutResult(status -> {
            context.publishEvent(new ChallengePurchasePaidEvent(42L));
            status.setRollbackOnly();
        });

        verify(REPOSITORY, after(500).never()).findById(any());
        verify(REPOSITORY, never()).markEmailSent(anyLong(), any());
    }

    @Configuration
    @EnableAsync
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean
        ChallengePurchaseRepository challengePurchaseRepository() {
            return REPOSITORY;
        }

        @Bean
        ChallengeAccessEmailComposer challengeAccessEmailComposer() {
            return mock(ChallengeAccessEmailComposer.class);
        }

        @Bean
        EmailSender emailSender() {
            return mock(EmailSender.class);
        }

        @Bean
        ChallengePurchaseEmailService challengePurchaseEmailService(
                ChallengePurchaseRepository repository, ChallengeAccessEmailComposer composer, EmailSender sender) {
            return new ChallengePurchaseEmailService(repository, composer, sender);
        }
    }

    /** Begins/commits/rolls back nothing, but drives Spring's real transaction synchronizations. */
    static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
