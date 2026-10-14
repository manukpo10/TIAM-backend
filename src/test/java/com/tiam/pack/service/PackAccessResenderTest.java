package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.challenge.config.WhatsAppProperties;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.repository.PackOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackAccessResenderTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");

    @Mock PackOrderRepository orders;
    @Mock PackOrderService packOrderService;
    @Mock EmailSender emailSender;
    @Mock ChallengePurchaseService challengePurchases;

    PackProperties properties;
    PackAccessResender resender;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setLandingBaseUrl("https://tiamdigital.com");
        PackAccessEmailComposer composer = new PackAccessEmailComposer(properties, new WhatsAppProperties());
        resender = new PackAccessResender(orders, packOrderService, composer, emailSender, challengePurchases);
    }

    @Test
    void resendNow_rotatesTheToken_andEmailsTheNewLink() {
        PackOrder paid = paidOrder(42L, "old-token");
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc("ana@example.com", PackOrderStatus.PAID))
                .thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(true);
        PackOrder rotated = paidOrder(42L, "new-token-" + PackTokens.newToken().substring(10));
        when(packOrderService.rotateAccess(42L)).thenReturn(Optional.of(new PackOrderService.AccessRotation(rotated, "old-token", NOW)));
        when(emailSender.send(any())).thenReturn(true);

        boolean sent = resender.resendNow("ana@example.com");

        assertThat(sent).isTrue();
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("ana@example.com");
        assertThat(captor.getValue().subject()).isEqualTo("Tu nuevo acceso al Pack TIAM");
        assertThat(captor.getValue().html()).contains("https://tiamdigital.com/pack/descargas/" + rotated.getAccessToken());
        assertThat(captor.getValue().html()).doesNotContain("old-token");
        assertThat(captor.getValue().idempotencyKey()).startsWith("pack-access-resend/42/");
    }

    @Test
    void resendNow_aDeliveredResendAlsoRecordsTheEmailAsSent_soTheRetryJobNeverSendsALateDuplicate() {
        PackOrder paid = paidOrder(42L, "old-token");
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any())).thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(true);
        PackOrder rotated = paidOrder(42L, PackTokens.newToken());
        when(packOrderService.rotateAccess(42L)).thenReturn(Optional.of(new PackOrderService.AccessRotation(rotated, "old-token", NOW)));
        when(emailSender.send(any())).thenReturn(true);

        resender.resendNow("ana@example.com");

        verify(orders).markEmailSent(eq(42L), any(Instant.class)); // conditional on email_sent_at still being null
    }

    @Test
    void resendNow_aFailedResendRecordsNothing() {
        PackOrder paid = paidOrder(42L, "old-token");
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any())).thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(true);
        PackOrder rotated = paidOrder(42L, PackTokens.newToken());
        when(packOrderService.rotateAccess(42L)).thenReturn(Optional.of(new PackOrderService.AccessRotation(rotated, "old-token", NOW)));
        when(emailSender.send(any())).thenReturn(false);

        resender.resendNow("ana@example.com");

        verify(orders, never()).markEmailSent(any(), any());
    }

    @Test
    void resendNow_usesTheMostRecentPaidOrderOnly() {
        PackOrder newest = paidOrder(43L, "t43");
        PackOrder older = paidOrder(42L, "t42");
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc("ana@example.com", PackOrderStatus.PAID))
                .thenReturn(List.of(newest, older));
        when(emailSender.isEnabled()).thenReturn(true);
        PackOrder rotated = paidOrder(43L, PackTokens.newToken());
        when(packOrderService.rotateAccess(43L)).thenReturn(Optional.of(new PackOrderService.AccessRotation(rotated, "t43", NOW)));
        when(emailSender.send(any())).thenReturn(true);

        resender.resendNow("ana@example.com");

        verify(packOrderService).rotateAccess(43L);
        verify(packOrderService, never()).rotateAccess(42L);
    }

    @Test
    void resendNow_noPaidOrderForThatEmail_doesNothing_quietly() {
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any())).thenReturn(List.of());

        assertThat(resender.resendNow("nobody@example.com")).isFalse();

        verifyNoInteractions(packOrderService, emailSender);
    }

    @Test
    void resendNow_whenTheEmailCannotBeDelivered_theOldLinkIsRestored_soTheBuyerIsNotLockedOut() {
        PackOrder paid = paidOrder(42L, "old-token");
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any())).thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(true);
        PackOrder rotated = paidOrder(42L, PackTokens.newToken());
        Instant previousExpiry = NOW.plusSeconds(3600);
        when(packOrderService.rotateAccess(42L))
                .thenReturn(Optional.of(new PackOrderService.AccessRotation(rotated, "old-token", previousExpiry)));
        when(emailSender.send(any())).thenReturn(false);

        assertThat(resender.resendNow("ana@example.com")).isFalse();

        verify(packOrderService).restoreAccess(42L, rotated.getAccessToken(), "old-token", previousExpiry);
    }

    @Test
    void resendNow_withoutARealEmailProvider_doesNotRotateAnything() {
        PackOrder paid = paidOrder(42L, "old-token");
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any())).thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(false);

        assertThat(resender.resendNow("ana@example.com")).isFalse();

        verify(packOrderService, never()).rotateAccess(any());
        verify(emailSender, never()).send(any());
    }

    @Test
    void resendNow_withAnUnusableDownloadBase_doesNotRotateAnything() {
        PackOrder paid = paidOrder(42L, "old-token");
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any())).thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(true);
        properties.setDownloadBaseUrl("http://localhost:5173/pack/descargas");

        assertThat(resender.resendNow("ana@example.com")).isFalse();

        verify(packOrderService, never()).rotateAccess(any());
    }

    @Test
    void resendNow_withGames_includesTheDesafioLink() {
        PackOrder paid = paidOrder(42L, "old-token");
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any())).thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(true);
        PackOrder rotated = paidOrder(42L, PackTokens.newToken());
        rotated.setWithGames(true);
        rotated.setChallengePurchaseId(77L);
        when(packOrderService.rotateAccess(42L)).thenReturn(Optional.of(new PackOrderService.AccessRotation(rotated, "old-token", NOW)));
        when(challengePurchases.findAccessLinkIfActive(77L)).thenReturn(Optional.of("https://tiamdigital.com/desafio/tok-77"));
        when(emailSender.send(any())).thenReturn(true);

        resender.resendNow("ana@example.com");

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().html()).contains("https://tiamdigital.com/desafio/tok-77");
    }

    @Test
    void resendNow_anOrderThatStoppedBeingPaidMeanwhile_isNotEmailed() {
        PackOrder paid = paidOrder(42L, "old-token");
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any())).thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(true);
        when(packOrderService.rotateAccess(42L)).thenReturn(Optional.empty()); // e.g. refunded in the meantime

        assertThat(resender.resendNow("ana@example.com")).isFalse();

        verify(emailSender, never()).send(any());
    }

    @Test
    void resendNow_neverThrows() {
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(resender.resendNow("ana@example.com")).isFalse();
    }

    @Test
    void resendNow_looksTheEmailUpNormalised() {
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(eq("ana@example.com"), any())).thenReturn(List.of());

        resender.resendNow("  Ana@Example.COM ");

        verify(orders).findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc("ana@example.com", PackOrderStatus.PAID);
    }

    private static PackOrder paidOrder(long id, String token) {
        PackOrder order = new PackOrder();
        order.setId(id);
        order.setStatus(PackOrderStatus.PAID);
        order.setAccessToken(token);
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        order.setAccessExpiresAt(NOW.plusSeconds(86400));
        return order;
    }
}
