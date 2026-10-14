package com.tiam.pack.service;

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

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.domain.PackOrderPaidEvent;
import com.tiam.pack.repository.PackOrderRepository;
import java.time.Clock;
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
 * Proves the Spring wiring that unit tests calling the methods directly cannot: the pack access email fires
 * only AFTER the publishing transaction commits, never when it rolls back, and on a different thread than the
 * one that published (so the webhook and the buyer's return page are not held up). Bare Spring context with a
 * no-op transaction manager: no database.
 */
class PackOrderEmailListenerWiringTest {

    private static final PackOrderRepository REPOSITORY = mock(PackOrderRepository.class);

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
            context.publishEvent(new PackOrderPaidEvent(42L));
            verifyNoInteractions(REPOSITORY); // still inside the transaction: nothing may have happened yet
        });

        verify(REPOSITORY, timeout(3000)).findById(42L);
        assertThat(listenerThread.get()).isNotNull().isNotEqualTo(publisherThread);
    }

    @Test
    void rolledBackTransaction_neverRunsTheListener() {
        transactionTemplate.executeWithoutResult(status -> {
            context.publishEvent(new PackOrderPaidEvent(42L));
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
        PackOrderEmailService packOrderEmailService() {
            return new PackOrderEmailService(REPOSITORY, mock(PackAccessEmailComposer.class), mock(EmailSender.class),
                    mock(ChallengePurchaseService.class), Clock.systemUTC());
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
