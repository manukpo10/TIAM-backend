package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mercadopago.resources.payment.Payment;
import com.mercadopago.serialization.Serializer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * {@link PaymentSnapshot#from} against JSON shaped like Mercado Pago's {@code GET /v1/payments/{id}} response,
 * parsed by the SDK exactly as production does. The fixtures under {@code src/test/resources/mp} are MODELLED on
 * the documented response (every field the SDK knows, plus the ones it ignores); they were not captured from a
 * live account, so the live test should replace one with a real capture. What these protect: the SDK keeps
 * mapping {@code transaction_amount} (not {@code total_paid_amount}) and {@code currency_id} onto what the amount
 * check compares, and unknown fields never break the parse.
 */
class PaymentSnapshotTest {

    @Test
    void approvedPayment_exposesTheSellerAmountStatusReferenceAndCurrency() throws Exception {
        PaymentSnapshot snapshot = snapshot("payment-approved.json");

        assertThat(snapshot.paymentId()).isEqualTo("1234567890");
        assertThat(snapshot.status()).isEqualTo("approved");
        assertThat(snapshot.amount()).isEqualByComparingTo("14990");
        assertThat(snapshot.currency()).isEqualTo("ARS");
        assertThat(snapshot.externalReference()).isEqualTo("pack:42");
    }

    @Test
    void aFinancedPayment_isComparedBySellerAmount_notByWhatTheBuyerPaidWithInterest() throws Exception {
        PaymentSnapshot snapshot = snapshot("payment-approved-financed.json");

        assertThat(snapshot.amount()).isEqualByComparingTo(new BigDecimal("14990")); // total_paid_amount is 17988
    }

    @Test
    void rejectedPendingAndRefundedStatuses_comeThroughAsMercadoPagoNamesThem() throws Exception {
        assertThat(snapshot("payment-rejected.json").status()).isEqualTo("rejected");
        assertThat(snapshot("payment-pending-ticket.json").status()).isEqualTo("pending");
        assertThat(snapshot("payment-refunded.json").status()).isEqualTo("refunded");
    }

    @Test
    void theIdIsTheOneMercadoPagoReports_notTheTextThatArrivedInTheRequest() throws Exception {
        Payment payment = parse("payment-approved.json");

        // "0123" and "123" name the same payment; the order's unique payment id must be the canonical one, or the
        // same payment looks like two different ones and triggers a false "duplicate payment" alert.
        assertThat(PaymentSnapshot.from("001234567890", payment).paymentId()).isEqualTo("1234567890");
        assertThat(PaymentSnapshot.from("1234567890", payment).paymentId()).isEqualTo("1234567890");
    }

    @Test
    void aPaymentWithoutAnId_fallsBackToTheRequestedOne() {
        Payment payment = new Payment();

        assertThat(PaymentSnapshot.from("555", payment).paymentId()).isEqualTo("555");
    }

    @Test
    void anotherCurrency_isReportedAsIs_soTheAmountCheckCanRejectIt() throws Exception {
        assertThat(snapshot("payment-approved-usd.json").currency()).isEqualTo("USD");
    }

    @Test
    void aCouponStyleDiscount_showsAsADifferentAmount_whichTheOrderServiceHoldsForReview() throws Exception {
        // HYPOTHETICAL until the live test shows which amount Mercado Pago reports with a coupon.
        assertThat(snapshot("payment-approved-with-coupon-hypothetical.json").amount()).isEqualByComparingTo("13490");
    }

    private static PaymentSnapshot snapshot(String fixture) throws Exception {
        Payment payment = parse(fixture);
        return PaymentSnapshot.from(String.valueOf(payment.getId()), payment);
    }

    private static Payment parse(String fixture) throws Exception {
        String json = new String(new ClassPathResource("mp/" + fixture).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return Serializer.deserializeFromJson(Payment.class, json);
    }
}
