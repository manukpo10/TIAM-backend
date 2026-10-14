package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackOwnerNotifierTest {

    @Mock EmailSender emailSender;

    PackProperties properties;
    PackOwnerNotifier notifier;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        notifier = new PackOwnerNotifier(emailSender, properties);
    }

    @Test
    void notify_sendsAnEmailToTheOwnerAddress() {
        properties.setOwnerNotifyTo("owner@example.com");
        when(emailSender.send(any())).thenReturn(true);

        notifier.notify("Pedido 42 en revisión", "El monto no coincide.");

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("owner@example.com");
        assertThat(captor.getValue().subject()).isEqualTo("[TIAM Packs] Pedido 42 en revisión");
        assertThat(captor.getValue().text()).contains("El monto no coincide.");
    }

    @Test
    void notify_escapesTheBodyInTheHtmlVersion() {
        properties.setOwnerNotifyTo("owner@example.com");
        when(emailSender.send(any())).thenReturn(true);

        notifier.notify("Aviso", "<script>alert(1)</script> & más");

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void notify_fallsBackToTheRegretAddress() {
        properties.setRegretNotifyTo("regret@example.com");
        when(emailSender.send(any())).thenReturn(true);

        notifier.notify("Aviso", "Texto");

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("regret@example.com");
    }

    @Test
    void notify_withNoAddressConfigured_onlyLogs() {
        notifier.notify("Aviso", "Texto");

        verify(emailSender, never()).send(any());
    }

    @Test
    void notify_neverThrows_evenWhenTheSenderBlowsUp() {
        properties.setOwnerNotifyTo("owner@example.com");
        when(emailSender.send(any())).thenThrow(new IllegalStateException("provider exploded"));

        notifier.notify("Aviso", "Texto"); // must not propagate into the payment flow
    }
}
