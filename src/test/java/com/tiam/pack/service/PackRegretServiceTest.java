package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.email.EmailMessage;
import com.tiam.common.email.EmailSender;
import com.tiam.common.exception.BadRequestException;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackRegretRequest;
import com.tiam.pack.dto.RegretReceiptResponse;
import com.tiam.pack.dto.RegretRequest;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.pack.repository.PackRegretRequestRepository;
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

@ExtendWith(MockitoExtension.class)
class PackRegretServiceTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:30:00Z");

    @Mock PackRegretRequestRepository regrets;
    @Mock PackOrderRepository orders;
    @Mock EmailSender emailSender;
    @Mock ChallengePurchaseService challengePurchases;

    PackProperties properties;
    PackRegretService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.setRegretNotifyTo("owner@example.com");
        service = new PackRegretService(regrets, orders, challengePurchases, new PackRegretEmailComposer(), emailSender,
                properties, new PackRateLimiter(new MutableClock(NOW)));
    }

    // --- recording ---------------------------------------------------------------------------------------

    @Test
    void submit_storesTheRequest_andReturnsAReceiptWithAReference() {
        givenSaveAssignsId(12L);

        RegretReceiptResponse receipt = service.submit(request("Ana Gómez", "ana@example.com", null, "No lo necesito"), "1.2.3.4");

        ArgumentCaptor<PackRegretRequest> captor = ArgumentCaptor.forClass(PackRegretRequest.class);
        verify(regrets).save(captor.capture());
        assertThat(captor.getValue().getFullName()).isEqualTo("Ana Gómez");
        assertThat(captor.getValue().getEmail()).isEqualTo("ana@example.com");
        assertThat(captor.getValue().getReason()).isEqualTo("No lo necesito");
        assertThat(captor.getValue().getStatus()).isEqualTo("RECEIVED");
        assertThat(receipt.reference()).isEqualTo("ARR-000012");
        assertThat(receipt.receivedAt()).isEqualTo(NOW);
    }

    @Test
    void submit_normalisesAndBoundsWhatWasTyped() {
        givenSaveAssignsId(1L);

        service.submit(request("  Ana    Gómez  ", "  Ana@Example.COM ", "  x".repeat(100), "motivo\u0000con\ncontrol" + "!".repeat(5000)), "1.2.3.4");

        ArgumentCaptor<PackRegretRequest> captor = ArgumentCaptor.forClass(PackRegretRequest.class);
        verify(regrets).save(captor.capture());
        PackRegretRequest saved = captor.getValue();
        assertThat(saved.getFullName()).isEqualTo("Ana Gómez");
        assertThat(saved.getEmail()).isEqualTo("ana@example.com");
        assertThat(saved.getOrderRef()).hasSizeLessThanOrEqualTo(100);
        assertThat(saved.getReason()).hasSizeLessThanOrEqualTo(2000).doesNotContain("\u0000").contains("\n");
    }

    @Test
    void submit_aBlankOrderRefAndReasonAreStoredAsNull() {
        givenSaveAssignsId(1L);

        service.submit(request("Ana Gómez", "ana@example.com", "  ", ""), "1.2.3.4");

        ArgumentCaptor<PackRegretRequest> captor = ArgumentCaptor.forClass(PackRegretRequest.class);
        verify(regrets).save(captor.capture());
        assertThat(captor.getValue().getOrderRef()).isNull();
        assertThat(captor.getValue().getReason()).isNull();
    }

    @Test
    void submit_invalidEmailOrName_isABadRequest_andNothingIsStoredOrSent() {
        assertThatThrownBy(() -> service.submit(request("Ana Gómez", "not-an-email", null, null), "1.2.3.4"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.submit(request("<script>", "ana@example.com", null, null), "1.2.3.4"))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(regrets, emailSender);
    }

    // --- linking to an order -----------------------------------------------------------------------------------

    @Test
    void submit_linksTheOrder_whenTheNumberMatchesAnOrderOfTheSameEmail() {
        givenSaveAssignsId(1L);
        PackOrder order = orderOf(42L, "ana@example.com");
        when(orders.findById(42L)).thenReturn(Optional.of(order));

        service.submit(request("Ana Gómez", "ana@example.com", "42", null), "1.2.3.4");

        ArgumentCaptor<PackRegretRequest> captor = ArgumentCaptor.forClass(PackRegretRequest.class);
        verify(regrets).save(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(42L);
    }

    @Test
    void submit_understandsTheCommonWaysToWriteAnOrderNumber() {
        givenSaveAssignsId(1L);
        properties.getRateLimits().setRegretPerEmail(100);
        properties.getRateLimits().setRegretPerIp(100);
        when(orders.findById(42L)).thenReturn(Optional.of(orderOf(42L, "ana@example.com")));

        for (String ref : new String[] {"#42", "pack:42", "TIAM-42", "tiam 42", " 42 "}) {
            service.submit(request("Ana Gómez", "ana@example.com", ref, null), "1.2.3." + ref.hashCode());
        }

        verify(orders, times(5)).findById(42L);
    }

    @Test
    void submit_doesNotLinkSomeoneElsesOrder_evenIfTheNumberExists() {
        givenSaveAssignsId(1L);
        when(orders.findById(42L)).thenReturn(Optional.of(orderOf(42L, "someone.else@example.com")));

        service.submit(request("Ana Gómez", "ana@example.com", "42", null), "1.2.3.4");

        ArgumentCaptor<PackRegretRequest> captor = ArgumentCaptor.forClass(PackRegretRequest.class);
        verify(regrets).save(captor.capture());
        assertThat(captor.getValue().getOrderId()).isNull();
        assertThat(captor.getValue().getOrderRef()).isEqualTo("42"); // what was typed is kept either way
    }

    @Test
    void submit_aNumberThatIsNotAnOrder_isStillRecorded() {
        givenSaveAssignsId(1L);

        service.submit(request("Ana Gómez", "ana@example.com", "no sé el número", null), "1.2.3.4");

        verify(orders, never()).findById(any());
        verify(regrets).save(any());
    }

    // --- the two emails ----------------------------------------------------------------------------------------------

    @Test
    void submit_sendsAKnownBuyerAReceiptAndTheOwnerANotice() {
        givenSaveAssignsId(12L);
        givenKnownPackBuyer("ana@example.com");
        when(emailSender.send(any())).thenReturn(true);

        service.submit(request("Ana Gómez", "ana@example.com", "42", "No lo necesito"), "1.2.3.4");

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(2)).send(captor.capture());
        List<EmailMessage> sent = captor.getAllValues();
        EmailMessage toBuyer = sent.stream().filter(m -> m.to().equals("ana@example.com")).findFirst().orElseThrow();
        EmailMessage toOwner = sent.stream().filter(m -> m.to().equals("owner@example.com")).findFirst().orElseThrow();
        assertThat(toBuyer.subject()).contains("ARR-000012");
        assertThat(toBuyer.text()).contains("ARR-000012");
        assertThat(toBuyer.idempotencyKey()).isEqualTo("pack-regret/12");
        assertThat(toOwner.subject()).contains("ARR-000012");
        assertThat(toOwner.text()).contains("ana@example.com").contains("42").contains("No lo necesito");
        assertThat(toOwner.idempotencyKey()).isEqualTo("pack-regret-owner/12");
    }

    @Test
    void submit_anAddressThatNeverBought_getsNoEmailAtAll_butTheOwnerIsAlwaysTold() {
        givenSaveAssignsId(12L);
        when(emailSender.send(any())).thenReturn(true);

        RegretReceiptResponse receipt = service.submit(
                request("Phisher", "victim@example.com", "42", "Hacé clic en http://evil.example.com"), "1.2.3.4");

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(1)).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("owner@example.com");
        assertThat(captor.getValue().text()).contains("Comprador conocido: no");
        assertThat(receipt.reference()).isEqualTo("ARR-000012"); // the buyer still gets the constancia on screen
    }

    @Test
    void submit_aDesafioBuyer_isAlsoAKnownBuyer() {
        givenSaveAssignsId(12L);
        when(challengePurchases.hasPaidPurchaseWithEmail("ana@example.com")).thenReturn(true);
        when(emailSender.send(any())).thenReturn(true);

        service.submit(request("Ana Gómez", "ana@example.com", null, null), "1.2.3.4");

        verify(emailSender, times(2)).send(any());
    }

    @Test
    void submit_theBuyerReceiptNeverRepeatsWhatWasTyped_soItCannotCarryAttackerText() {
        givenSaveAssignsId(12L);
        givenKnownPackBuyer("ana@example.com");
        when(emailSender.send(any())).thenReturn(true);

        service.submit(request("Visitá http://evil.example.com ahora", "ana@example.com", "pack:42 evil-ref",
                "Pagá a la cuenta 123 evil-reason"), "1.2.3.4");

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(2)).send(captor.capture());
        EmailMessage toBuyer = captor.getAllValues().stream().filter(m -> m.to().equals("ana@example.com")).findFirst().orElseThrow();
        assertThat(toBuyer.text()).doesNotContain("evil").doesNotContain("Visitá").doesNotContain("123");
        assertThat(toBuyer.html()).doesNotContain("evil").doesNotContain("Visitá");
    }

    @Test
    void submit_aGlobalDailyCapOnBuyerReceipts_stillRecordsAndStillTellsTheOwner() {
        givenSaveAssignsId(12L);
        properties.getRateLimits().setRegretReceiptsPerDay(2);
        properties.getRateLimits().setRegretPerIp(100);
        for (int i = 0; i < 4; i++) {
            givenKnownPackBuyer("buyer" + i + "@example.com");
        }
        when(emailSender.send(any())).thenReturn(true);

        for (int i = 0; i < 4; i++) {
            service.submit(request("Ana Gómez", "buyer" + i + "@example.com", null, null), "9.9.9." + i);
        }

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(2 + 4)).send(captor.capture()); // 2 receipts + 4 owner notices
        assertThat(captor.getAllValues().stream().filter(m -> !m.to().equals("owner@example.com"))).hasSize(2);
        verify(regrets, times(4)).save(any());
    }

    @Test
    void submit_withNoOwnerAddress_stillRecordsAndStillSendsTheKnownBuyerReceipt() {
        properties.setRegretNotifyTo("");
        givenSaveAssignsId(12L);
        givenKnownPackBuyer("ana@example.com");
        when(emailSender.send(any())).thenReturn(true);

        service.submit(request("Ana Gómez", "ana@example.com", null, null), "1.2.3.4");

        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailSender, times(1)).send(captor.capture());
        assertThat(captor.getValue().to()).isEqualTo("ana@example.com");
    }

    @Test
    void submit_aFailingEmailProvider_neverFailsTheRequest_becauseItIsAlreadyRecorded() {
        givenSaveAssignsId(12L);
        when(emailSender.send(any())).thenThrow(new IllegalStateException("provider down"));

        RegretReceiptResponse receipt = service.submit(request("Ana Gómez", "ana@example.com", null, null), "1.2.3.4");

        assertThat(receipt.reference()).isEqualTo("ARR-000012");
    }

    // --- abuse limits --------------------------------------------------------------------------------------------------

    @Test
    void submit_perIpLimit_isEnforced() {
        givenSaveAssignsId(1L);
        properties.getRateLimits().setRegretPerIp(2);

        service.submit(request("Ana Gómez", "a1@example.com", null, null), "1.2.3.4");
        service.submit(request("Ana Gómez", "a2@example.com", null, null), "1.2.3.4");

        assertThatThrownBy(() -> service.submit(request("Ana Gómez", "a3@example.com", null, null), "1.2.3.4"))
                .isInstanceOf(PackRateLimitedException.class);
    }

    @Test
    void submit_perEmailLimit_stopsUsFromBeingUsedToMailBombSomeone() {
        givenSaveAssignsId(1L);
        properties.getRateLimits().setRegretPerEmail(2);

        service.submit(request("Ana Gómez", "victim@example.com", null, null), "1.1.1.1");
        service.submit(request("Ana Gómez", "victim@example.com", null, null), "2.2.2.2");

        assertThatThrownBy(() -> service.submit(request("Ana Gómez", "victim@example.com", null, null), "3.3.3.3"))
                .isInstanceOf(PackRateLimitedException.class);
        verify(regrets, times(2)).save(any());
    }

    // --- helpers -----------------------------------------------------------------------------------------------------------------

    private void givenKnownPackBuyer(String email) {
        when(orders.existsByEmailAndStatusInAndActivoTrue(eq(email), any())).thenReturn(true);
    }

    private void givenSaveAssignsId(long id) {
        when(regrets.save(any(PackRegretRequest.class))).thenAnswer(invocation -> {
            PackRegretRequest saved = invocation.getArgument(0);
            saved.setId(id);
            saved.setCreatedAt(NOW);
            return saved;
        });
    }

    private static RegretRequest request(String fullName, String email, String orderRef, String reason) {
        return new RegretRequest(fullName, email, orderRef, reason);
    }

    private static PackOrder orderOf(long id, String email) {
        PackOrder order = new PackOrder();
        order.setId(id);
        order.setEmail(email);
        return order;
    }
}
