package com.tiam.challenge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchasePaidEvent;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.testsupport.LogCapture;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The send-once logic behind the "Tu Desafío TIAM está listo" email. */
@ExtendWith(MockitoExtension.class)
class ChallengePurchaseEmailServiceTest {

    private static final Long ID = 42L;
    private static final EmailMessage MESSAGE =
            new EmailMessage("buyer@example.com", "Tu Desafío TIAM está listo", "<p>hi</p>", "hi", "challenge-access/42");

    @Mock ChallengePurchaseRepository repository;
    @Mock ChallengeAccessEmailComposer composer;
    @Mock EmailSender emailSender;

    ChallengePurchaseEmailService service;

    @BeforeEach
    void setUp() {
        service = new ChallengePurchaseEmailService(repository, composer, emailSender);
    }

    @Test
    void paidPurchaseWithEmail_isSentAndRecorded() {
        ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", null);
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));
        when(composer.compose(purchase)).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(true);
        when(repository.markEmailSent(eq(ID), any(Instant.class))).thenReturn(1);

        boolean sent = service.sendAccessEmail(ID);

        assertThat(sent).isTrue();
        verify(emailSender).send(MESSAGE);
        verify(repository).markEmailSent(eq(ID), any(Instant.class));
    }

    @Test
    void alreadyEmailed_isNeverSentAgain() {
        ChallengePurchase purchase =
                purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", Instant.now().minusSeconds(60));
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));

        boolean sent = service.sendAccessEmail(ID);

        assertThat(sent).isFalse();
        verifyNoInteractions(composer, emailSender);
        verify(repository, never()).markEmailSent(anyLong(), any());
    }

    @Test
    void theSameEventDeliveredTwice_sendsExactlyOneEmail() {
        // The second delivery sees what the first one recorded, as it would from the DB.
        ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", null);
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));
        when(composer.compose(purchase)).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(true);
        when(repository.markEmailSent(eq(ID), any(Instant.class))).thenAnswer(invocation -> {
            purchase.setEmailSentAt(invocation.getArgument(1));
            return 1;
        });

        service.onPurchasePaid(new ChallengePurchasePaidEvent(ID));
        service.onPurchasePaid(new ChallengePurchasePaidEvent(ID));

        verify(emailSender, times(1)).send(any(EmailMessage.class));
        verify(repository, times(1)).markEmailSent(eq(ID), any(Instant.class));
    }

    @Test
    void buyerWithoutEmail_isSkipped() {
        for (String missing : new String[] {null, "", "   "}) {
            ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, missing, null);
            when(repository.findById(ID)).thenReturn(Optional.of(purchase));

            assertThat(service.sendAccessEmail(ID)).isFalse();
        }

        verifyNoInteractions(composer, emailSender);
        verify(repository, never()).markEmailSent(anyLong(), any());
    }

    @Test
    void providerDidNotDeliver_isNotRecordedAsSent() {
        // Provider unconfigured (logging fallback), HTTP error, timeout... all return false:
        // email_sent_at must stay NULL so the owner can see who never got the email.
        ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", null);
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));
        when(composer.compose(purchase)).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(false);

        boolean sent = service.sendAccessEmail(ID);

        assertThat(sent).isFalse();
        verify(repository, never()).markEmailSent(anyLong(), any());
    }

    @Test
    void purchaseThatIsNotPaid_isSkipped() {
        for (ChallengePurchaseStatus status : new ChallengePurchaseStatus[] {
                ChallengePurchaseStatus.PENDING, ChallengePurchaseStatus.FAILED}) {
            when(repository.findById(ID))
                    .thenReturn(Optional.of(purchase(status, "buyer@example.com", null)));

            assertThat(service.sendAccessEmail(ID)).isFalse();
        }

        verifyNoInteractions(composer, emailSender);
    }

    @Test
    void unknownPurchase_isSkipped() {
        when(repository.findById(ID)).thenReturn(Optional.empty());

        assertThat(service.sendAccessEmail(ID)).isFalse();

        verifyNoInteractions(composer, emailSender);
    }

    // --- DESAFIO_PLAY_BASE_URL guard (localhost links must never be emailed) -----------

    @Test
    void providerLive_butLinkBaseIsLocalhostOrNotHttps_doesNotSendAndDoesNotRecord() {
        ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", null);
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));
        when(emailSender.isEnabled()).thenReturn(true);
        when(composer.hasPublicAccessLinkBase()).thenReturn(false);
        when(composer.accessLinkBase()).thenReturn("http://localhost:5173/desafio");

        try (LogCapture logs = new LogCapture(ChallengePurchaseEmailService.class)) {
            boolean sent = service.sendAccessEmail(ID);

            assertThat(sent).isFalse();
            assertThat(logs.messages(Level.ERROR)).singleElement()
                    .satisfies(line -> assertThat(line).contains("DESAFIO_PLAY_BASE_URL").contains("NOT sent"));
        }
        verify(emailSender, never()).send(any());
        verify(composer, never()).compose(any());
        // email_sent_at untouched => the retry job can deliver once the config is fixed
        verify(repository, never()).markEmailSent(anyLong(), any());
    }

    @Test
    void afterTheLinkBaseIsFixed_theSamePurchaseIsDeliveredOnTheNextAttempt() {
        ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", null);
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));
        when(emailSender.isEnabled()).thenReturn(true);
        when(composer.hasPublicAccessLinkBase()).thenReturn(false, true);
        when(composer.accessLinkBase()).thenReturn("http://localhost:5173/desafio");
        when(composer.compose(purchase)).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(true);

        assertThat(service.sendAccessEmail(ID)).isFalse();
        assertThat(service.sendAccessEmail(ID)).isTrue();

        verify(emailSender, times(1)).send(MESSAGE);
        verify(repository, times(1)).markEmailSent(eq(ID), any(Instant.class));
    }

    @Test
    void loggingFallbackActive_theLinkBaseIsNotChecked() {
        // No real provider => nothing leaves the building, so a localhost base is irrelevant.
        ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", null);
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));
        when(emailSender.isEnabled()).thenReturn(false);
        when(composer.compose(purchase)).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(false);

        assertThat(service.sendAccessEmail(ID)).isFalse();

        verify(composer, never()).hasPublicAccessLinkBase();
    }

    @Test
    void startup_providerLiveAndLinkBaseUnusable_logsAnError() {
        when(emailSender.isEnabled()).thenReturn(true);
        when(composer.hasPublicAccessLinkBase()).thenReturn(false);
        when(composer.accessLinkBase()).thenReturn("http://localhost:5173/desafio");

        try (LogCapture logs = new LogCapture(ChallengePurchaseEmailService.class)) {
            service.checkLinkBaseAtStartup();

            assertThat(logs.messages(Level.ERROR)).singleElement().satisfies(line -> assertThat(line)
                    .contains("DESAFIO_PLAY_BASE_URL")
                    .contains("http://localhost:5173/desafio")
                    .contains("will NOT be sent"));
        }
    }

    @Test
    void startup_linkBaseUsableOrProviderOff_logsNothing() {
        try (LogCapture logs = new LogCapture(ChallengePurchaseEmailService.class)) {
            when(emailSender.isEnabled()).thenReturn(true);
            when(composer.hasPublicAccessLinkBase()).thenReturn(true);
            service.checkLinkBaseAtStartup();

            when(emailSender.isEnabled()).thenReturn(false);
            service.checkLinkBaseAtStartup();

            assertThat(logs.events()).isEmpty();
        }
    }

    // --- the listener must never break the payment flow ---------------------------

    @Test
    void listener_senderThrows_isLoggedAndSwallowed() {
        ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", null);
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));
        when(composer.compose(purchase)).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenThrow(new IllegalStateException("provider exploded"));

        assertThatCode(() -> service.onPurchasePaid(new ChallengePurchasePaidEvent(ID)))
                .doesNotThrowAnyException();
        verify(repository, never()).markEmailSent(anyLong(), any());
    }

    @Test
    void listener_databaseFailsWhileReading_isLoggedAndSwallowed() {
        when(repository.findById(ID)).thenThrow(new IllegalStateException("connection pool exhausted"));

        assertThatCode(() -> service.onPurchasePaid(new ChallengePurchasePaidEvent(ID)))
                .doesNotThrowAnyException();
        verifyNoInteractions(emailSender);
    }

    @Test
    void listener_databaseFailsWhileRecording_isLoggedAndSwallowed() {
        ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", null);
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));
        when(composer.compose(purchase)).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(true);
        when(repository.markEmailSent(eq(ID), any(Instant.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> service.onPurchasePaid(new ChallengePurchasePaidEvent(ID)))
                .doesNotThrowAnyException();
    }

    @Test
    void listener_composingFails_isLoggedAndSwallowed() {
        ChallengePurchase purchase = purchase(ChallengePurchaseStatus.PAID, "buyer@example.com", null);
        when(repository.findById(ID)).thenReturn(Optional.of(purchase));
        when(composer.compose(purchase)).thenThrow(new IllegalStateException("template broke"));

        assertThatCode(() -> service.onPurchasePaid(new ChallengePurchasePaidEvent(ID)))
                .doesNotThrowAnyException();
        verify(emailSender, never()).send(any());
    }

    private static ChallengePurchase purchase(ChallengePurchaseStatus status, String email, Instant emailSentAt) {
        ChallengePurchase purchase = new ChallengePurchase();
        purchase.setId(ID);
        purchase.setBuyerName("Ana Diaz");
        purchase.setPhone("5491122334455");
        purchase.setEmail(email);
        purchase.setStatus(status);
        purchase.setAccessToken("token");
        purchase.setPurchaseDate(Instant.now());
        purchase.setEmailSentAt(emailSentAt);
        return purchase;
    }
}
