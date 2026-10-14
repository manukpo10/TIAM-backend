package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOwnerAlert;
import com.tiam.testsupport.LogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class PackOwnerNotifierTest {

    @Mock ApplicationEventPublisher events;
    @Mock EmailSender emailSender;

    PackProperties properties;
    PackOwnerNotifier notifier;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        notifier = new PackOwnerNotifier(events, emailSender, properties);
    }

    // --- notify: no I/O, only an event ---------------------------------------------------------------------

    @Test
    void notify_onlyPublishesAnAlert_neverTouchesTheEmailProvider() {
        notifier.notify("Pedido 42 en revisión", "El monto no coincide.");

        ArgumentCaptor<PackOwnerAlert> captor = ArgumentCaptor.forClass(PackOwnerAlert.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new PackOwnerAlert("Pedido 42 en revisión", "El monto no coincide."));
        verifyNoInteractions(emailSender);
    }

    // --- deliver: the actual email, after commit --------------------------------------------------------------

    @Test
    void deliver_sendsAnEmailToTheOwnerAddress() {
        properties.setOwnerNotifyTo("owner@example.com");
        when(emailSender.send(any())).thenReturn(true);

        notifier.deliver(new PackOwnerAlert("Pedido 42 en revisión", "El monto no coincide."));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("owner@example.com");
        assertThat(captor.getValue().subject()).isEqualTo("[TIAM Packs] Pedido 42 en revisión");
        assertThat(captor.getValue().text()).contains("El monto no coincide.");
    }

    @Test
    void deliver_escapesTheBodyInTheHtmlVersion() {
        properties.setOwnerNotifyTo("owner@example.com");
        when(emailSender.send(any())).thenReturn(true);

        notifier.deliver(new PackOwnerAlert("Aviso", "<script>alert(1)</script> & más"));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void deliver_fallsBackToTheRegretAddress() {
        properties.setRegretNotifyTo("regret@example.com");
        when(emailSender.send(any())).thenReturn(true);

        notifier.deliver(new PackOwnerAlert("Aviso", "Texto"));

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("regret@example.com");
    }

    @Test
    void deliver_withNoAddressConfigured_logsOnlyTheSubject_neverTheBody() {
        try (LogCapture logs = new LogCapture(PackOwnerNotifier.class)) {
            notifier.deliver(new PackOwnerAlert("Pedido 42 sin teléfono", "Contactá a ana.gomez@example.com"));

            assertThat(logs.messages(Level.WARN)).anyMatch(m -> m.contains("Pedido 42 sin teléfono"));
            assertThat(logs.allMessages()).noneMatch(m -> m.contains("ana.gomez@example.com"));
        }
        verify(emailSender, never()).send(any());
    }

    @Test
    void deliver_neverThrows_evenWhenTheSenderBlowsUp() {
        properties.setOwnerNotifyTo("owner@example.com");
        when(emailSender.send(any())).thenThrow(new IllegalStateException("provider exploded"));

        notifier.deliver(new PackOwnerAlert("Aviso", "Texto")); // must not propagate
    }
}
