package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.tiam.pack.domain.PackOrderPaidEvent;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.testsupport.MutableClock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackOrderEmailServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");
    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";

    @Mock PackOrderRepository orders;
    @Mock EmailSender emailSender;
    @Mock ChallengePurchaseService challengePurchases;

    PackProperties properties;
    PackOrderEmailService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setLandingBaseUrl("https://tiamdigital.com");
        PackAccessEmailComposer composer = new PackAccessEmailComposer(properties, new WhatsAppProperties());
        service = new PackOrderEmailService(orders, composer, emailSender, challengePurchases, new MutableClock(NOW));
    }

    // --- sends once, after payment ----------------------------------------------------------------------

    @Test
    void sendAccessEmail_paidOrder_sendsOneEmailWithTheDownloadLink_andRecordsIt() {
        givenOrder(PackOrderStatus.PAID);
        givenDeliveryWorks();

        boolean sent = service.sendAccessEmail(42L);

        assertThat(sent).isTrue();
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("ana@example.com");
        assertThat(captor.getValue().html()).contains("https://tiamdigital.com/pack/descargas/" + TOKEN);
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("pack-access/42");
        verify(orders).markEmailSent(42L, NOW);
    }

    @Test
    void sendAccessEmail_withGames_putsTheDesafioLinkInTheSameEmail() {
        PackOrder order = givenOrder(PackOrderStatus.PAID);
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);
        when(challengePurchases.findAccessLinkIfActive(77L)).thenReturn(Optional.of("https://tiamdigital.com/desafio/tok-77"));
        givenDeliveryWorks();

        service.sendAccessEmail(42L);

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender).send(captor.capture());
        assertThat(captor.getValue().html()).contains("https://tiamdigital.com/desafio/tok-77");
    }

    @Test
    void sendAccessEmail_withoutGames_doesNotLookUpTheDesafio() {
        givenOrder(PackOrderStatus.PAID);
        givenDeliveryWorks();

        service.sendAccessEmail(42L);

        verifyNoInteractions(challengePurchases);
    }

    @Test
    void sendAccessEmail_neverSendsBeforeThePaymentIsConfirmed() {
        for (PackOrderStatus status : new PackOrderStatus[] {PackOrderStatus.PENDING, PackOrderStatus.FAILED,
                PackOrderStatus.REVIEW, PackOrderStatus.REVOKED}) {
            givenOrder(status);

            assertThat(service.sendAccessEmail(42L)).as("status %s", status).isFalse();
        }

        verifyNoInteractions(emailSender);
        verify(orders, never()).markEmailSent(anyLong(), any());
    }

    @Test
    void sendAccessEmail_anAlreadyEmailedOrder_isNotEmailedAgain() {
        PackOrder order = givenOrder(PackOrderStatus.PAID);
        order.setEmailSentAt(NOW.minusSeconds(60));

        assertThat(service.sendAccessEmail(42L)).isFalse();

        verifyNoInteractions(emailSender);
    }

    @Test
    void sendAccessEmail_unknownOrder_isANoOp() {
        when(orders.findById(42L)).thenReturn(Optional.empty());

        assertThat(service.sendAccessEmail(42L)).isFalse();

        verifyNoInteractions(emailSender);
    }

    @Test
    void sendAccessEmail_noEmailOnFile_isNotSent() {
        PackOrder order = givenOrder(PackOrderStatus.PAID);
        order.setEmail("  ");

        assertThat(service.sendAccessEmail(42L)).isFalse();

        verifyNoInteractions(emailSender);
    }

    // --- failure handling ----------------------------------------------------------------------------------

    @Test
    void sendAccessEmail_whenTheProviderRefuses_itIsNotRecorded_soTheRetryJobCanTryAgain() {
        givenOrder(PackOrderStatus.PAID);
        when(emailSender.isEnabled()).thenReturn(true);
        when(emailSender.send(any())).thenReturn(false);

        assertThat(service.sendAccessEmail(42L)).isFalse();

        verify(orders, never()).markEmailSent(anyLong(), any());
    }

    @Test
    void sendAccessEmail_withAnUnusableDownloadBase_isBlocked_untilTheConfigIsFixed() {
        givenOrder(PackOrderStatus.PAID);
        when(emailSender.isEnabled()).thenReturn(true);
        properties.setDownloadBaseUrl("http://localhost:5173/pack/descargas");

        assertThat(service.sendAccessEmail(42L)).isFalse();

        verify(emailSender, never()).send(any());
        verify(orders, never()).markEmailSent(anyLong(), any());
        assertThat(service.isBlockedByUnusableLinkBase()).isTrue();
    }

    @Test
    void onPaid_neverThrows_whateverGoesWrong() {
        when(orders.findById(42L)).thenThrow(new IllegalStateException("db down"));

        service.onPaid(new PackOrderPaidEvent(42L)); // must not propagate: it runs next to a payment
    }

    @Test
    void deliveryIsEnabledOnlyWhenARealProviderIsConfigured() {
        when(emailSender.isEnabled()).thenReturn(false);
        assertThat(service.isDeliveryEnabled()).isFalse();

        when(emailSender.isEnabled()).thenReturn(true);
        assertThat(service.isDeliveryEnabled()).isTrue();
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private PackOrder givenOrder(PackOrderStatus status) {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setStatus(status);
        order.setAccessToken(TOKEN);
        order.setEdition(PackEdition.FAMILIAS);
        order.setBuyerName("Ana Gómez");
        order.setEmail("ana@example.com");
        when(orders.findById(42L)).thenReturn(Optional.of(order));
        return order;
    }

    private void givenDeliveryWorks() {
        when(emailSender.isEnabled()).thenReturn(true);
        when(emailSender.send(any())).thenReturn(true);
        when(orders.markEmailSent(anyLong(), any())).thenReturn(1);
    }
}
