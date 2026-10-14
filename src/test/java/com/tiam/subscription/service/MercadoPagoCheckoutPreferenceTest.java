package com.tiam.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.serialization.Serializer;
import com.tiam.subscription.config.ChallengeProperties;
import com.tiam.subscription.config.MercadoPagoProperties;
import com.tiam.subscription.config.PlansProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The request a pack checkout sends to Mercado Pago, built without any network call. */
class MercadoPagoCheckoutPreferenceTest {

    private static final OffsetDateTime FROM = OffsetDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneOffset.ofHours(-3));
    private static final OffsetDateTime TO = FROM.plusDays(7);

    PlansProperties plans;
    MercadoPagoService service;

    @BeforeEach
    void setUp() {
        plans = new PlansProperties();
        plans.setNotificationUrl("https://api.example.com/webhooks/mercadopago");
        service = new MercadoPagoService(new MercadoPagoProperties(), plans, new ChallengeProperties());
    }

    @Test
    void everyLineBecomesItsOwnItem_withIdTitleDescriptionAndCategory() {
        PreferenceRequest request = service.buildCheckoutPreferenceRequest(spec(
                new CheckoutLine("pack-familias", "Pack TIAM Familias", "Pack imprimible", new BigDecimal("14990.00")),
                new CheckoutLine("juegos", "Mes de juegos", "Desafío 30 días", new BigDecimal("10000.00"))));

        assertThat(request.getItems()).hasSize(2);
        PreferenceItemRequest first = request.getItems().get(0);
        assertThat(first.getId()).isEqualTo("pack-familias");
        assertThat(first.getTitle()).isEqualTo("Pack TIAM Familias");
        assertThat(first.getDescription()).isEqualTo("Pack imprimible");
        assertThat(first.getCategoryId()).isEqualTo("virtual_goods");
        assertThat(first.getQuantity()).isEqualTo(1);
        assertThat(first.getCurrencyId()).isEqualTo("ARS");
        assertThat(first.getUnitPrice()).isEqualByComparingTo("14990");
        assertThat(request.getItems().get(1).getUnitPrice()).isEqualByComparingTo("10000");
    }

    @Test
    void theOrderIsCorrelatedThroughTheExternalReference() {
        PreferenceRequest request = service.buildCheckoutPreferenceRequest(spec(line()));

        assertThat(request.getExternalReference()).isEqualTo("pack:42");
    }

    @Test
    void successFailureAndPendingReturnToTheSameThankYouPage_withAutoReturn() {
        PreferenceRequest request = service.buildCheckoutPreferenceRequest(spec(line()));

        assertThat(request.getBackUrls().getSuccess()).isEqualTo("https://tiamdigital.com/pack/gracias?o=TOKEN");
        assertThat(request.getBackUrls().getFailure()).isEqualTo("https://tiamdigital.com/pack/gracias?o=TOKEN");
        assertThat(request.getBackUrls().getPending()).isEqualTo("https://tiamdigital.com/pack/gracias?o=TOKEN");
        assertThat(request.getAutoReturn()).isEqualTo("approved");
    }

    @Test
    void aLocalhostReturnUrl_leavesAutoReturnOut_becauseMercadoPagoRejectsThePreferenceOtherwise() {
        // Found in the local live test (PACK_DEV_MODE, landing on http://localhost): with auto_return=approved and a
        // non-public back_url the Mercado Pago API answers 400. Without auto_return the buyer clicks "Volver al sitio".
        CheckoutPreferenceSpec local = new CheckoutPreferenceSpec(List.of(line()), "ana@example.com", "Ana",
                "pack:42", "http://localhost:5180/pack/gracias?o=TOKEN", FROM, TO, "TIAM");

        PreferenceRequest request = service.buildCheckoutPreferenceRequest(local);

        assertThat(request.getAutoReturn()).isNull();
        assertThat(request.getBackUrls().getSuccess()).isEqualTo("http://localhost:5180/pack/gracias?o=TOKEN");
        assertThat(request.getBackUrls().getFailure()).isEqualTo("http://localhost:5180/pack/gracias?o=TOKEN");
        assertThat(request.getBackUrls().getPending()).isEqualTo("http://localhost:5180/pack/gracias?o=TOKEN");
    }

    @Test
    void notificationUrlComesFromTheSameSettingAsTheDesafio() {
        PreferenceRequest request = service.buildCheckoutPreferenceRequest(spec(line()));

        assertThat(request.getNotificationUrl()).isEqualTo("https://api.example.com/webhooks/mercadopago");
    }

    @Test
    void withoutANotificationUrl_noneIsSent() {
        plans.setNotificationUrl("");

        PreferenceRequest request = service.buildCheckoutPreferenceRequest(spec(line()));

        assertThat(request.getNotificationUrl()).isNull();
    }

    @Test
    void thePreferenceExpires_soCashCouponsDoNotStayPayableForever() {
        PreferenceRequest request = service.buildCheckoutPreferenceRequest(spec(line()));

        assertThat(request.getExpires()).isTrue();
        assertThat(request.getExpirationDateFrom()).isEqualTo(FROM);
        assertThat(request.getExpirationDateTo()).isEqualTo(TO);
    }

    @Test
    void theStatementDescriptorHelpsTheBuyerRecogniseTheCharge() {
        PreferenceRequest request = service.buildCheckoutPreferenceRequest(spec(line()));

        assertThat(request.getStatementDescriptor()).isEqualTo("TIAM");
    }

    @Test
    void theBuyerEmailAndNameArePassedAlong_splitIntoFirstNameAndSurname() {
        PreferenceRequest request = service.buildCheckoutPreferenceRequest(spec(line()));

        assertThat(request.getPayer().getEmail()).isEqualTo("ana@example.com");
        assertThat(request.getPayer().getName()).isEqualTo("Ana");
        assertThat(request.getPayer().getSurname()).isEqualTo("María Gómez");
    }

    @Test
    void aSingleWordName_hasNoSurname() {
        CheckoutPreferenceSpec single = new CheckoutPreferenceSpec(List.of(line()), "ana@example.com", "Ana",
                "pack:42", "https://tiamdigital.com/pack/gracias?o=TOKEN", FROM, TO, "TIAM");

        PreferenceRequest request = service.buildCheckoutPreferenceRequest(single);

        assertThat(request.getPayer().getName()).isEqualTo("Ana");
        assertThat(request.getPayer().getSurname()).isNull();
    }

    @Test
    void expirationDates_serializeInTheFormatMercadoPagoExpects() {
        PreferenceRequest request = service.buildCheckoutPreferenceRequest(spec(line()));

        JsonObject json = Serializer.serializeToJson(request);

        assertThat(json.get("expiration_date_to").getAsString())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{2}:\\d{2}");
        assertThat(json.get("external_reference").getAsString()).isEqualTo("pack:42");
    }

    private static CheckoutLine line() {
        return new CheckoutLine("pack-familias", "Pack TIAM Familias", "Pack imprimible", new BigDecimal("14990.00"));
    }

    private static CheckoutPreferenceSpec spec(CheckoutLine... lines) {
        return new CheckoutPreferenceSpec(List.of(lines), "ana@example.com", "Ana María Gómez", "pack:42",
                "https://tiamdigital.com/pack/gracias?o=TOKEN", FROM, TO, "TIAM");
    }
}
