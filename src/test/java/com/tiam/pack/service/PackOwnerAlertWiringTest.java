package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackProperties;
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
 * Owner alerts are raised inside payment transactions. This proves, with Spring's real event and transaction
 * machinery (no database), that the email goes out only AFTER the commit, never for a rolled-back
 * transaction, and never on the thread that holds the transaction.
 */
class PackOwnerAlertWiringTest {

    private static final EmailSender SENDER = mock(EmailSender.class);

    private AnnotationConfigApplicationContext context;
    private PackOwnerNotifier notifier;
    private TransactionTemplate transactionTemplate;
    private final AtomicReference<String> senderThread = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(SENDER);
        when(SENDER.send(any())).thenAnswer(invocation -> {
            senderThread.set(Thread.currentThread().getName());
            return true;
        });
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        notifier = context.getBean(PackOwnerNotifier.class);
        transactionTemplate = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void alertRaisedInsideATransaction_isEmailedAfterCommit_onAnotherThread() {
        String callerThread = Thread.currentThread().getName();

        transactionTemplate.executeWithoutResult(status -> {
            notifier.notify("Pedido 42 en revisión", "El monto no coincide.");
            verifyNoInteractions(SENDER); // still inside the transaction: nothing may have been sent
        });

        verify(SENDER, timeout(3000)).send(any());
        assertThat(senderThread.get()).isNotNull().isNotEqualTo(callerThread);
    }

    @Test
    void alertRaisedInATransactionThatRollsBack_isNeverEmailed() {
        transactionTemplate.executeWithoutResult(status -> {
            notifier.notify("Pedido 42 en revisión", "El monto no coincide.");
            status.setRollbackOnly();
        });

        verify(SENDER, after(500).never()).send(any());
    }

    @Test
    void alertRaisedWithoutAnyTransaction_isStillEmailed() {
        notifier.notify("Aviso", "Sin transacción");

        verify(SENDER, timeout(3000)).send(any());
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
        PackOwnerNotifier packOwnerNotifier(org.springframework.context.ApplicationEventPublisher publisher) {
            PackProperties properties = new PackProperties();
            properties.setOwnerNotifyTo("owner@example.com");
            return new PackOwnerNotifier(publisher, SENDER, properties);
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
