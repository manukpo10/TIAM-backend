package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tiam.common.email.EmailMessage;
import com.tiam.pack.domain.PackRegretRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PackRegretEmailComposerTest {

    PackRegretEmailComposer composer = new PackRegretEmailComposer();

    @Test
    void buyerReceipt_isAConstancia_withReferenceDateAndWhatHappensNext() {
        EmailMessage message = composer.buyerReceipt(request("Ana Gómez", "ana@example.com", "42", "motivo"), "ARR-000012");

        assertThat(message.to()).isEqualTo("ana@example.com");
        assertThat(message.subject()).isEqualTo("Recibimos tu solicitud de arrepentimiento (ARR-000012)");
        assertThat(message.text()).contains("ARR-000012");
        assertThat(message.text()).contains("14/10/2026"); // date in Argentina time
        assertThat(message.text()).contains("42");
        assertThat(message.text()).contains("constancia");
        assertThat(message.html()).contains("ARR-000012");
        assertThat(message.idempotencyKey()).isEqualTo("pack-regret/12");
    }

    @Test
    void buyerReceipt_escapesWhatTheBuyerTyped() {
        EmailMessage message = composer.buyerReceipt(
                request("<img src=x onerror=alert(1)>", "ana@example.com", "<b>42</b>", null), "ARR-000012");

        assertThat(message.html()).doesNotContain("<img src=x").doesNotContain("<b>42</b>");
        assertThat(message.html()).contains("&lt;img");
    }

    @Test
    void ownerNotice_carriesEverythingNeededToActOnIt() {
        EmailMessage message = composer.ownerNotice(
                request("Ana Gómez", "ana@example.com", "42", "No lo necesito"), "ARR-000012", "owner@example.com");

        assertThat(message.to()).isEqualTo("owner@example.com");
        assertThat(message.subject()).isEqualTo("[TIAM Packs] Arrepentimiento ARR-000012");
        assertThat(message.text()).contains("Ana Gómez", "ana@example.com", "42", "No lo necesito", "ARR-000012");
        assertThat(message.idempotencyKey()).isEqualTo("pack-regret-owner/12");
    }

    @Test
    void ownerNotice_escapesTheHtml() {
        EmailMessage message = composer.ownerNotice(
                request("Ana", "ana@example.com", null, "<script>alert(1)</script>"), "ARR-000012", "owner@example.com");

        assertThat(message.html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void ownerNotice_saysSoWhenNoReasonOrOrderWasGiven() {
        EmailMessage message = composer.ownerNotice(request("Ana", "ana@example.com", null, null), "ARR-000012", "owner@example.com");

        assertThat(message.text()).contains("(no indicó)");
    }

    private static PackRegretRequest request(String fullName, String email, String orderRef, String reason) {
        PackRegretRequest request = new PackRegretRequest();
        request.setId(12L);
        request.setFullName(fullName);
        request.setEmail(email);
        request.setOrderRef(orderRef);
        request.setReason(reason);
        request.setCreatedAt(Instant.parse("2026-10-14T15:30:00Z"));
        return request;
    }
}
