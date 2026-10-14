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
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.pack.storage.PrivateFileStore;
import com.tiam.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(MockitoExtension.class)
class PackAccessResenderTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");

    @Mock PackOrderRepository orders;
    @Mock PackOrderService packOrderService;
    @Mock EmailSender emailSender;
    @Mock ChallengePurchaseService challengePurchases;

    PackProperties properties;
    MockEnvironment environment;
    MutableClock clock;
    PackAccessResender resender;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setLandingBaseUrl("https://tiamdigital.com");
        clock = new MutableClock(NOW);
        environment = new MockEnvironment();
        PackSalesReadiness readiness = new PackSalesReadiness(properties, org.mockito.Mockito.mock(PrivateFileStore.class),
                emailSender, environment);
        PackAccessEmailComposer composer = new PackAccessEmailComposer(properties, new WhatsAppProperties(), readiness);
        resender = new PackAccessResender(orders, packOrderService, composer, emailSender, challengePurchases,
                new PackRateLimiter(clock), properties, clock);
    }

    @Test
    void resendNow_issuesAReplacementToken_andEmailsItsLink_leavingTheWorkingLinkAlone() {
        PackOrder paid = paidOrder(42L, "current-token");
        givenPaid("ana@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(true);
        String replacement = PackTokens.newToken();
        when(packOrderService.issueReplacementToken(42L)).thenReturn(Optional.of(new PackOrderService.ReplacementToken(paid, replacement)));
        when(emailSender.send(any())).thenReturn(true);

        boolean sent = resender.resendNow("ana@example.com");

        assertThat(sent).isTrue();
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("ana@example.com");
        assertThat(captor.getValue().subject()).isEqualTo("Tu acceso al Pack TIAM");
        assertThat(captor.getValue().html()).contains("https://tiamdigital.com/pack/descargas/" + replacement);
        assertThat(captor.getValue().html()).doesNotContain("current-token");
        assertThat(captor.getValue().idempotencyKey()).startsWith("pack-access-resend/42/");
        assertThat(paid.getAccessToken()).as("nothing was invalidated").isEqualTo("current-token");
    }

    @Test
    void resendNow_aBuyerOfBothEditions_getsEveryLinkInOneEmail() {
        PackOrder newest = paidOrder(43L, "t43");
        newest.setEdition(PackEdition.PROFESIONALES);
        PackOrder older = paidOrder(42L, "t42");
        givenPaid("ana@example.com", newest, older);
        when(emailSender.isEnabled()).thenReturn(true);
        String r43 = PackTokens.newToken();
        String r42 = PackTokens.newToken();
        when(packOrderService.issueReplacementToken(43L)).thenReturn(Optional.of(new PackOrderService.ReplacementToken(newest, r43)));
        when(packOrderService.issueReplacementToken(42L)).thenReturn(Optional.of(new PackOrderService.ReplacementToken(older, r42)));
        when(emailSender.send(any())).thenReturn(true);

        resender.resendNow("ana@example.com");

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, org.mockito.Mockito.times(1)).send(captor.capture());
        assertThat(captor.getValue().text()).contains("/" + r43).contains("/" + r42)
                .contains("Edición Profesionales").contains("Edición Familias");
    }

    @Test
    void resendNow_aDeliveredResendAlsoRecordsTheEmailAsSent_soTheRetryJobNeverSendsALateDuplicate() {
        PackOrder paid = paidOrder(42L, "old-token");
        givenPaid("ana@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(true);
        when(packOrderService.issueReplacementToken(42L))
                .thenReturn(Optional.of(new PackOrderService.ReplacementToken(paid, PackTokens.newToken())));
        when(emailSender.send(any())).thenReturn(true);

        resender.resendNow("ana@example.com");

        verify(orders).markEmailSent(eq(42L), any(Instant.class)); // conditional on email_sent_at still being null
    }

    @Test
    void resendNow_aFailedResendRecordsNothing_andThereIsNothingToUndo() {
        PackOrder paid = paidOrder(42L, "old-token");
        givenPaid("ana@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(true);
        when(packOrderService.issueReplacementToken(42L))
                .thenReturn(Optional.of(new PackOrderService.ReplacementToken(paid, PackTokens.newToken())));
        when(emailSender.send(any())).thenReturn(false);

        assertThat(resender.resendNow("ana@example.com")).isFalse();

        verify(orders, never()).markEmailSent(any(), any());
        assertThat(paid.getAccessToken()).isEqualTo("old-token");
    }

    @Test
    void resendNow_noPaidOrderForThatEmail_doesNothing_andSpendsNoQuota() {
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(anyString(), any())).thenReturn(List.of());
        properties.getRateLimits().setResendPerEmail(1);

        for (int i = 0; i < 5; i++) {
            assertThat(resender.resendNow("nobody@example.com")).isFalse();
        }

        verifyNoInteractions(packOrderService, emailSender);
        // the quota of a stranger's typo is untouched: when this address does buy, it still has its full allowance
        PackOrder paid = paidOrder(42L, "t");
        givenPaid("nobody@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(true);
        when(packOrderService.issueReplacementToken(42L))
                .thenReturn(Optional.of(new PackOrderService.ReplacementToken(paid, PackTokens.newToken())));
        when(emailSender.send(any())).thenReturn(true);
        assertThat(resender.resendNow("nobody@example.com")).isTrue();
    }

    @Test
    void resendNow_theEmailQuotaIsSpentOnlyForRealBuyers_andThenDropsSilently() {
        PackOrder paid = paidOrder(42L, "t");
        givenPaid("ana@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(true);
        when(packOrderService.issueReplacementToken(42L))
                .thenReturn(Optional.of(new PackOrderService.ReplacementToken(paid, PackTokens.newToken())));
        when(emailSender.send(any())).thenReturn(true);
        properties.getRateLimits().setResendPerEmail(2);

        assertThat(resender.resendNow("ana@example.com")).isTrue();
        assertThat(resender.resendNow("ana@example.com")).isTrue();
        assertThat(resender.resendNow("ana@example.com")).isFalse();

        verify(emailSender, org.mockito.Mockito.times(2)).send(any());
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        assertThat(resender.resendNow("ana@example.com")).isTrue();
    }

    @Test
    void resendNow_withoutARealEmailProvider_issuesNothing() {
        PackOrder paid = paidOrder(42L, "old-token");
        givenPaid("ana@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(false);

        assertThat(resender.resendNow("ana@example.com")).isFalse();

        verify(packOrderService, never()).issueReplacementToken(any());
        verify(emailSender, never()).send(any());
    }

    @Test
    void resendNow_withAnUnusableDownloadBase_issuesNothing() {
        PackOrder paid = paidOrder(42L, "old-token");
        givenPaid("ana@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(true);
        properties.setDownloadBaseUrl("http://localhost:5173/pack/descargas");

        assertThat(resender.resendNow("ana@example.com")).isFalse();

        verify(packOrderService, never()).issueReplacementToken(any());
    }

    @Test
    void resendNow_inDevMode_emailsAnHttpLocalhostLink_soTheLocalTestLinkOpensTheLocalOrder() {
        properties.setDevMode(true);
        properties.setDownloadBaseUrl("http://localhost:5180/pack/descargas");
        PackOrder paid = paidOrder(42L, "old-token");
        givenPaid("ana@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(true);
        String replacement = PackTokens.newToken();
        when(packOrderService.issueReplacementToken(42L)).thenReturn(Optional.of(new PackOrderService.ReplacementToken(paid, replacement)));
        when(emailSender.send(any())).thenReturn(true);

        assertThat(resender.resendNow("ana@example.com")).isTrue();

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().html()).contains("http://localhost:5180/pack/descargas/" + replacement);
    }

    @Test
    void resendNow_devModeFlagInTheProdProfile_stillRefusesALocalhostLink() {
        properties.setDevMode(true);
        environment.setActiveProfiles("prod");
        properties.setDownloadBaseUrl("http://localhost:5180/pack/descargas");
        givenPaid("ana@example.com", paidOrder(42L, "old-token"));
        when(emailSender.isEnabled()).thenReturn(true);

        assertThat(resender.resendNow("ana@example.com")).isFalse();

        verify(packOrderService, never()).issueReplacementToken(any());
        verify(emailSender, never()).send(any());
    }

    @Test
    void resendNow_withGames_includesTheDesafioLink() {
        PackOrder paid = paidOrder(42L, "old-token");
        paid.setWithGames(true);
        paid.setChallengePurchaseId(77L);
        givenPaid("ana@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(true);
        when(packOrderService.issueReplacementToken(42L))
                .thenReturn(Optional.of(new PackOrderService.ReplacementToken(paid, PackTokens.newToken())));
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
        givenPaid("ana@example.com", paid);
        when(emailSender.isEnabled()).thenReturn(true);
        when(packOrderService.issueReplacementToken(42L)).thenReturn(Optional.empty()); // e.g. refunded in the meantime

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

    private void givenPaid(String email, PackOrder... paid) {
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(email, PackOrderStatus.PAID)).thenReturn(List.of(paid));
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
