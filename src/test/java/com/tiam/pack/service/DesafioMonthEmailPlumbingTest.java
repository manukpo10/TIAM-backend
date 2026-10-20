package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

/**
 * The services that send the access emails hand the composer the Desafío month the order was ACTUALLY given (its link
 * and its number), and ask the Desafío about nothing when no month was granted.
 */
@ExtendWith(MockitoExtension.class)
class DesafioMonthEmailPlumbingTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");
    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";

    @Mock PackOrderRepository orders;
    @Mock EmailSender emailSender;
    @Mock ChallengePurchaseService challengePurchases;
    @Mock PackOrderService packOrderService;

    PackOrderEmailService emailService;
    PackAccessResender resender;

    @BeforeEach
    void setUp() {
        PackProperties properties = new PackProperties();
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setLandingBaseUrl("https://tiamdigital.com");
        MutableClock clock = new MutableClock(NOW);
        PackSalesReadiness readiness = new PackSalesReadiness(properties, org.mockito.Mockito.mock(PrivateFileStore.class),
                emailSender, new MockEnvironment());
        PackAccessEmailComposer composer = new PackAccessEmailComposer(properties, new WhatsAppProperties(), readiness);
        emailService = new PackOrderEmailService(orders, composer, emailSender, challengePurchases, clock);
        resender = new PackAccessResender(orders, packOrderService, composer, emailSender, challengePurchases,
                new PackRateLimiter(clock), properties, clock);
    }

    @Test
    void theFirstEmail_carriesTheGrantedMonthNumberAndLink() {
        PackOrder order = order(PackEdition.CUADERNOS);
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);
        when(challengePurchases.findAccessLinkIfActive(77L)).thenReturn(Optional.of("https://tiamdigital.com/desafio/tok-77"));
        when(challengePurchases.findMonthIfActive(77L)).thenReturn(Optional.of(2));
        givenDeliveryWorks();

        emailService.sendAccessEmail(42L);

        EmailMessage sent = sentMessage();
        assertThat(sent.html()).contains("Mes 2 del Desafío").contains("https://tiamdigital.com/desafio/tok-77");
        assertThat(sent.text()).contains("Mes 2 del Desafío");
    }

    @Test
    void theFirstEmail_whenNoMonthWasGranted_doesNotAskTheDesafioAndDoesNotMentionIt() {
        PackOrder order = order(PackEdition.CUADERNOS);
        order.setWithGames(true); // paid for it, but no month was granted: the owner is told to refund
        givenDeliveryWorks();

        emailService.sendAccessEmail(42L);

        verifyNoInteractions(challengePurchases);
        EmailMessage sent = sentMessage();
        assertThat(sent.html()).doesNotContain("Desafío");
        assertThat(sent.text()).doesNotContain("Desafío");
    }

    @Test
    void theLostLinkEmail_carriesTheGrantedMonthNumber() {
        PackOrder paid = order(PackEdition.CUADERNOS);
        paid.setStatus(PackOrderStatus.PAID);
        paid.setWithGames(true);
        paid.setChallengePurchaseId(77L);
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc("ana@example.com", PackOrderStatus.PAID))
                .thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(true);
        when(packOrderService.issueReplacementToken(42L))
                .thenReturn(Optional.of(new PackOrderService.ReplacementToken(paid, PackTokens.newToken())));
        when(challengePurchases.findAccessLinkIfActive(77L)).thenReturn(Optional.of("https://tiamdigital.com/desafio/tok-77"));
        when(challengePurchases.findMonthIfActive(77L)).thenReturn(Optional.of(3));
        when(emailSender.send(any())).thenReturn(true);

        resender.resendNow("ana@example.com");

        EmailMessage sent = sentMessage();
        assertThat(sent.html()).contains("Mes 3 del Desafío");
    }

    @Test
    void theLostLinkEmail_whenNoMonthWasGranted_doesNotAskTheDesafioAndDoesNotMentionIt() {
        PackOrder paid = order(PackEdition.CUADERNOS);
        paid.setStatus(PackOrderStatus.PAID);
        paid.setWithGames(true);
        when(orders.findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc("ana@example.com", PackOrderStatus.PAID))
                .thenReturn(List.of(paid));
        when(emailSender.isEnabled()).thenReturn(true);
        when(packOrderService.issueReplacementToken(42L))
                .thenReturn(Optional.of(new PackOrderService.ReplacementToken(paid, PackTokens.newToken())));
        when(emailSender.send(any())).thenReturn(true);

        resender.resendNow("ana@example.com");

        verifyNoInteractions(challengePurchases);
        assertThat(sentMessage().html()).doesNotContain("Desafío");
    }

    // ---------------------------------------------------------------------------------------------------------------------

    private EmailMessage sentMessage() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        return captor.getValue();
    }

    private PackOrder order(PackEdition edition) {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setStatus(PackOrderStatus.PAID);
        order.setAccessToken(TOKEN);
        order.setEdition(edition);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        order.setAccessExpiresAt(NOW.plusSeconds(86400));
        org.mockito.Mockito.lenient().when(orders.findById(42L)).thenReturn(Optional.of(order));
        return order;
    }

    private void givenDeliveryWorks() {
        when(emailSender.isEnabled()).thenReturn(true);
        when(emailSender.send(any())).thenReturn(true);
        when(orders.markEmailSent(anyLong(), any())).thenReturn(1);
    }
}
