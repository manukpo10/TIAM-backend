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
import com.tiam.challenge.config.ChallengeEmailRetryProperties;
import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import com.tiam.challenge.repository.ChallengePurchaseRepository;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.testsupport.LogCapture;
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

/** The safety net that re-sends the access email to paid purchases the immediate send missed. */
@ExtendWith(MockitoExtension.class)
class ChallengeAccessEmailRetryServiceTest {

    private static final EmailMessage MESSAGE =
            new EmailMessage("buyer@example.com", "Tu Desafío TIAM está listo", "<p>hi</p>", "hi", "k");

    @Mock ChallengePurchaseRepository repository;
    @Mock ChallengeAccessEmailComposer composer;
    @Mock EmailSender emailSender;

    ChallengeEmailRetryProperties properties;
    ChallengePurchaseEmailService emailService;
    ChallengeAccessEmailRetryService retry;

    @BeforeEach
    void setUp() {
        properties = new ChallengeEmailRetryProperties();
        // The real send-once service on top of mocked collaborators: the retry's safety
        // comes from the combination, so it is tested as one.
        emailService = new ChallengePurchaseEmailService(repository, composer, emailSender);
        retry = new ChallengeAccessEmailRetryService(repository, emailService, properties);
    }

    private void givenLiveProviderWithUsableLinkBase() {
        when(emailSender.isEnabled()).thenReturn(true);
        when(composer.hasPublicAccessLinkBase()).thenReturn(true);
    }

    @Test
    void asksOnlyForPaidPurchasesInsideTheLastSeventyTwoHours_leavingTheFreshestToTheImmediateSend() {
        givenLiveProviderWithUsableLinkBase();
        when(repository.findIdsAwaitingAccessEmail(any(), any(), any())).thenReturn(List.of());

        Instant before = Instant.now();
        retry.retryUndeliveredAccessEmails();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> until = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findIdsAwaitingAccessEmail(eq(ChallengePurchaseStatus.PAID), since.capture(), until.capture());
        assertThat(since.getValue()).isBetween(
                before.minus(Duration.ofHours(72)), after.minus(Duration.ofHours(72)));
        assertThat(until.getValue()).isBetween(
                before.minus(Duration.ofMinutes(5)), after.minus(Duration.ofMinutes(5)));
    }

    @Test
    void retriesEveryCandidateThroughTheSendOnceService() {
        givenLiveProviderWithUsableLinkBase();
        ChallengePurchase first = purchase(1L, ChallengePurchaseStatus.PAID, "a@example.com", null);
        ChallengePurchase second = purchase(2L, ChallengePurchaseStatus.PAID, "b@example.com", null);
        when(repository.findIdsAwaitingAccessEmail(any(), any(), any())).thenReturn(List.of(1L, 2L));
        when(repository.findById(1L)).thenReturn(Optional.of(first));
        when(repository.findById(2L)).thenReturn(Optional.of(second));
        when(composer.compose(any())).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(true);

        retry.retryUndeliveredAccessEmails();

        verify(emailSender, times(2)).send(MESSAGE);
        verify(repository).markEmailSent(eq(1L), any(Instant.class));
        verify(repository).markEmailSent(eq(2L), any(Instant.class));
    }

    @Test
    void candidatesThatTheQueryLetThroughButAreNotEligible_areStillNeverSent() {
        // Defence in depth: even if the query ever returned them, the send-once service
        // re-checks every purchase against the live row before sending.
        givenLiveProviderWithUsableLinkBase();
        when(repository.findIdsAwaitingAccessEmail(any(), any(), any())).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(repository.findById(1L)).thenReturn(Optional.of(
                purchase(1L, ChallengePurchaseStatus.PAID, "ok@example.com", null)));                   // eligible
        when(repository.findById(2L)).thenReturn(Optional.of(
                purchase(2L, ChallengePurchaseStatus.PAID, "sent@example.com", Instant.now())));        // already emailed
        when(repository.findById(3L)).thenReturn(Optional.of(
                purchase(3L, ChallengePurchaseStatus.PAID, "  ", null)));                               // no email
        when(repository.findById(4L)).thenReturn(Optional.of(
                purchase(4L, ChallengePurchaseStatus.PENDING, "pending@example.com", null)));           // not paid
        when(repository.findById(5L)).thenReturn(Optional.empty());                                     // vanished
        when(composer.compose(any())).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(true);

        retry.retryUndeliveredAccessEmails();

        verify(emailSender, times(1)).send(MESSAGE);
        verify(repository, times(1)).markEmailSent(anyLong(), any());
        verify(repository).markEmailSent(eq(1L), any(Instant.class));
    }

    @Test
    void isIdempotent_runningTwiceSendsEachPurchaseOnce() {
        // Worst case: the query keeps returning the purchase on the second run (e.g. a
        // stale read). The live row now carries email_sent_at, so nothing is re-sent.
        givenLiveProviderWithUsableLinkBase();
        ChallengePurchase purchase = purchase(1L, ChallengePurchaseStatus.PAID, "a@example.com", null);
        when(repository.findIdsAwaitingAccessEmail(any(), any(), any())).thenReturn(List.of(1L));
        when(repository.findById(1L)).thenReturn(Optional.of(purchase));
        when(composer.compose(purchase)).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(true);
        when(repository.markEmailSent(eq(1L), any(Instant.class))).thenAnswer(invocation -> {
            purchase.setEmailSentAt(invocation.getArgument(1));
            return 1;
        });

        retry.retryUndeliveredAccessEmails();
        retry.retryUndeliveredAccessEmails();

        verify(emailSender, times(1)).send(any(EmailMessage.class));
        verify(repository, times(1)).markEmailSent(anyLong(), any());
    }

    @Test
    void aFailedDelivery_staysUnrecordedSoTheNextRunTriesAgain() {
        givenLiveProviderWithUsableLinkBase();
        ChallengePurchase purchase = purchase(1L, ChallengePurchaseStatus.PAID, "a@example.com", null);
        when(repository.findIdsAwaitingAccessEmail(any(), any(), any())).thenReturn(List.of(1L));
        when(repository.findById(1L)).thenReturn(Optional.of(purchase));
        when(composer.compose(purchase)).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(false, true);   // Resend 5xx first, fine afterwards

        retry.retryUndeliveredAccessEmails();
        verify(repository, never()).markEmailSent(anyLong(), any());

        retry.retryUndeliveredAccessEmails();
        verify(repository, times(1)).markEmailSent(eq(1L), any(Instant.class));
        verify(emailSender, times(2)).send(MESSAGE);
    }

    @Test
    void oneFailingPurchase_doesNotStopTheOthers() {
        givenLiveProviderWithUsableLinkBase();
        ChallengePurchase first = purchase(1L, ChallengePurchaseStatus.PAID, "a@example.com", null);
        ChallengePurchase third = purchase(3L, ChallengePurchaseStatus.PAID, "c@example.com", null);
        when(repository.findIdsAwaitingAccessEmail(any(), any(), any())).thenReturn(List.of(1L, 2L, 3L));
        when(repository.findById(1L)).thenReturn(Optional.of(first));
        when(repository.findById(2L)).thenThrow(new IllegalStateException("connection pool exhausted"));
        when(repository.findById(3L)).thenReturn(Optional.of(third));
        when(composer.compose(any())).thenReturn(MESSAGE);
        when(emailSender.send(MESSAGE)).thenReturn(true);

        assertThatCode(() -> retry.retryUndeliveredAccessEmails()).doesNotThrowAnyException();

        verify(repository).markEmailSent(eq(1L), any(Instant.class));
        verify(repository).markEmailSent(eq(3L), any(Instant.class));
    }

    @Test
    void aFailingQuery_isLoggedAndNeverEscapesTheScheduler() {
        givenLiveProviderWithUsableLinkBase();
        when(repository.findIdsAwaitingAccessEmail(any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> retry.retryUndeliveredAccessEmails()).doesNotThrowAnyException();
    }

    @Test
    void providerNotConfigured_doesNotQueryOrSend() {
        // Logging fallback: nothing can be delivered, so the run is a quiet no-op and the
        // purchases stay eligible until RESEND_API_KEY / EMAIL_FROM are set.
        when(emailSender.isEnabled()).thenReturn(false);

        retry.retryUndeliveredAccessEmails();

        verifyNoInteractions(repository, composer);
        verify(emailSender, never()).send(any());
    }

    @Test
    void localhostLinkBase_logsOneErrorAndSendsNothing() {
        when(emailSender.isEnabled()).thenReturn(true);
        when(composer.hasPublicAccessLinkBase()).thenReturn(false);

        try (LogCapture logs = new LogCapture(ChallengeAccessEmailRetryService.class)) {
            retry.retryUndeliveredAccessEmails();

            assertThat(logs.messages(Level.ERROR)).singleElement()
                    .satisfies(line -> assertThat(line).contains("DESAFIO_PLAY_BASE_URL"));
        }
        verifyNoInteractions(repository);
        verify(emailSender, never()).send(any());
    }

    @Test
    void disabledByProperty_isANoOp() {
        properties.setEnabled(false);

        retry.retryUndeliveredAccessEmails();

        verifyNoInteractions(repository, composer, emailSender);
    }

    private static ChallengePurchase purchase(Long id, ChallengePurchaseStatus status, String email, Instant emailSentAt) {
        ChallengePurchase purchase = new ChallengePurchase();
        purchase.setId(id);
        purchase.setBuyerName("Ana Diaz");
        purchase.setPhone("5491122334455");
        purchase.setEmail(email);
        purchase.setStatus(status);
        purchase.setAccessToken("token-" + id);
        purchase.setMpPaymentId("mp-" + id);
        purchase.setPurchaseDate(Instant.now().minus(Duration.ofHours(1)));
        purchase.setEmailSentAt(emailSentAt);
        return purchase;
    }
}
