package com.tiam.subscription.web;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.net.MPResponse;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preapproval.Preapproval;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.pack.service.PackOrderService;
import com.tiam.pack.service.PaymentSnapshot;
import com.tiam.subscription.config.MercadoPagoProperties;
import com.tiam.subscription.service.MercadoPagoService;
import com.tiam.subscription.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the Task 1 P0 fix: the webhook must return a status code that lets Mercado
 * Pago's retry mechanism actually work. Before this fix, {@code handleWebhook} caught
 * every exception and always returned 200 — which meant a transient MP API failure
 * (rate limit, network blip) silently dropped the notification forever, since MP only
 * retries on non-200 responses.
 */
@ExtendWith(MockitoExtension.class)
class MercadoPagoWebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String REQUEST_ID = "req-123";

    @Mock MercadoPagoService mercadoPagoService;
    @Mock SubscriptionService subscriptionService;
    @Mock ChallengePurchaseService challengePurchaseService;
    @Mock MercadoPagoProperties mercadoPagoProperties;
    @Mock PackOrderService packOrderService;

    MercadoPagoWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new MercadoPagoWebhookController(
                mercadoPagoService, subscriptionService, challengePurchaseService, mercadoPagoProperties, packOrderService);
    }

    // --- signature validation ---------------------------------------------------

    @Test
    void handleWebhook_validSignature_isAccepted() {
        when(mercadoPagoProperties.getWebhookSecret()).thenReturn(SECRET);
        MockHttpServletRequest request = signedRequest("999", SECRET);

        // Unrecognized type so there's no downstream service call to stub — this test
        // isolates signature acceptance from business-logic processing.
        ResponseEntity<Void> response =
                controller.handleWebhook(null, "merchant_order", null, "999", null, request);

        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void handleWebhook_invalidSignature_returns401() {
        when(mercadoPagoProperties.getWebhookSecret()).thenReturn(SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-signature", "ts=1704908010,v1=deadbeef");
        request.addHeader("x-request-id", REQUEST_ID);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "999", null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(challengePurchaseService, subscriptionService);
    }

    @Test
    void handleWebhook_secretNotConfigured_allowsRequestThrough() throws Exception {
        // Documents the CURRENT deliberate fail-open behavior — explicitly out of scope
        // to change (MP_WEBHOOK_SECRET is not yet set in production).
        when(mercadoPagoProperties.getWebhookSecret()).thenReturn(null);
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment payment = approvedPayment("42");
        when(mercadoPagoService.getPayment("999")).thenReturn(payment);
        MockHttpServletRequest request = new MockHttpServletRequest(); // no signature headers at all

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "999", null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(challengePurchaseService).markPaid(42L, "999");
    }

    // --- payment status handling -------------------------------------------------

    @Test
    void handleWebhook_approvedPayment_marksPaidAndReturns200() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment payment = approvedPayment("42");
        when(mercadoPagoService.getPayment("555")).thenReturn(payment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(challengePurchaseService).markPaid(42L, "555");
    }

    @Test
    void handleWebhook_rejectedPayment_marksFailedAndReturns200() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment payment = paymentWithStatus("42", "rejected");
        when(mercadoPagoService.getPayment("555")).thenReturn(payment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(challengePurchaseService).markFailed(42L);
    }

    @Test
    void handleWebhook_cancelledPayment_marksFailedAndReturns200() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment payment = paymentWithStatus("42", "cancelled");
        when(mercadoPagoService.getPayment("555")).thenReturn(payment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(challengePurchaseService).markFailed(42L);
    }

    @Test
    void handleWebhook_paymentStatusInProcess_noServiceCallAndReturns200() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment payment = paymentWithStatus("42", "in_process");
        when(mercadoPagoService.getPayment("555")).thenReturn(payment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(challengePurchaseService);
    }

    @Test
    void handleWebhook_unrecognizedNotificationType_noServiceCallAndReturns200() {
        givenNoSignatureCheck();

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "merchant_order", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(challengePurchaseService, subscriptionService);
    }

    // --- externalReference validation --------------------------------------------

    @Test
    void handleWebhook_missingExternalReference_noServiceCallAndReturns200() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment payment = mock(Payment.class);
        when(payment.getExternalReference()).thenReturn(null);
        when(mercadoPagoService.getPayment("555")).thenReturn(payment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(challengePurchaseService);
    }

    @Test
    void handleWebhook_nonNumericExternalReference_noServiceCallAndReturns200() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment payment = approvedPayment("not-a-number");
        when(mercadoPagoService.getPayment("555")).thenReturn(payment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(challengePurchaseService);
    }

    // --- transient failure -> 5xx (Task 1 regression guard) -----------------------

    @Test
    void handleWebhook_getPaymentThrows_returns5xx() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(mercadoPagoService.getPayment("555")).thenThrow(new MPException("MP API blip"));

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        verifyNoInteractions(challengePurchaseService);
    }

    @Test
    void handleWebhook_getPreapprovalThrows_returns5xx() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(mercadoPagoService.getPreapproval("555")).thenThrow(new MPException("MP API blip"));

        ResponseEntity<Void> response = controller.handleWebhook(
                null, "subscription_preapproval", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        verifyNoInteractions(subscriptionService);
    }

    // --- permanent vs transient failures (SHOULD-FIX from adversarial review) -----

    @Test
    void handleWebhook_resourceNotFoundException_returns200AndDoesNotRetry() throws Exception {
        // markPaid/markFailed throw ResourceNotFoundException when the local id encoded
        // in externalReference doesn't exist. Retrying can never fix that — ack instead
        // of looping forever.
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment payment = approvedPayment("42");
        when(mercadoPagoService.getPayment("555")).thenReturn(payment);
        doThrow(new ResourceNotFoundException("Challenge purchase not found: 42"))
                .when(challengePurchaseService).markPaid(42L, "555");

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(challengePurchaseService).markPaid(42L, "555");
    }

    @Test
    void handleWebhook_mpApiException404_returns200() throws Exception {
        // MP itself says the resource doesn't exist (e.g. MP dashboard's simulated test
        // notifications use fabricated ids) — permanent, retrying is futile.
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(mercadoPagoService.getPayment("555"))
                .thenThrow(new MPApiException("Not Found", new MPResponse(404, null, null)));

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(challengePurchaseService);
    }

    @Test
    void handleWebhook_mpApiException500_returns5xx() throws Exception {
        // A genuine MP-side error — transient, worth retrying.
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(mercadoPagoService.getPayment("555"))
                .thenThrow(new MPApiException("Internal error", new MPResponse(500, null, null)));

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
    }

    @Test
    void handleWebhook_mpApiException401_returns5xx() throws Exception {
        // Bad/expired credentials — worth retrying once the operator fixes the token.
        // Must NOT be treated as permanent like a 404.
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(mercadoPagoService.getPayment("555"))
                .thenThrow(new MPApiException("Unauthorized", new MPResponse(401, null, null)));

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
    }

    @Test
    void handleWebhook_genericRuntimeException_returns5xx() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        when(mercadoPagoService.getPayment("555")).thenThrow(new RuntimeException("boom"));

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
    }

    // --- printable packs: routed by the "pack:" prefix, Desafio untouched -----------------------

    @Test
    void handleWebhook_packReference_isRoutedToThePackOrderService_notToTheChallenge() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment stubbedPayment = packPayment("pack:42", "approved");
        when(mercadoPagoService.getPayment("555")).thenReturn(stubbedPayment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<PaymentSnapshot> snapshot = ArgumentCaptor.forClass(PaymentSnapshot.class);
        verify(packOrderService).onPayment(org.mockito.ArgumentMatchers.eq(42L), snapshot.capture());
        assertThat(snapshot.getValue().paymentId()).isEqualTo("555");
        assertThat(snapshot.getValue().status()).isEqualTo("approved");
        assertThat(snapshot.getValue().amount()).isEqualByComparingTo("14990.00");
        assertThat(snapshot.getValue().currency()).isEqualTo("ARS");
        verifyNoInteractions(challengePurchaseService);
    }

    @Test
    void handleWebhook_packRefundIsRoutedToo_withItsStatus() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment stubbedPayment = packPayment("pack:42", "refunded");
        when(mercadoPagoService.getPayment("555")).thenReturn(stubbedPayment);

        controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        ArgumentCaptor<PaymentSnapshot> snapshot = ArgumentCaptor.forClass(PaymentSnapshot.class);
        verify(packOrderService).onPayment(org.mockito.ArgumentMatchers.eq(42L), snapshot.capture());
        assertThat(snapshot.getValue().status()).isEqualTo("refunded");
    }

    @Test
    void handleWebhook_numericReference_stillGoesToTheChallenge_andNeverToPacks() throws Exception {
        // Regression: the Desafio's external_reference is the bare purchase id and must keep working.
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment stubbedPayment = approvedPayment("42");
        when(mercadoPagoService.getPayment("555")).thenReturn(stubbedPayment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(challengePurchaseService).markPaid(42L, "555");
        verifyNoInteractions(packOrderService);
    }

    @Test
    void handleWebhook_numericReferenceRejected_stillMarksTheChallengeFailed_andNeverTouchesPacks() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment stubbedPayment = paymentWithStatus("42", "rejected");
        when(mercadoPagoService.getPayment("555")).thenReturn(stubbedPayment);

        controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        verify(challengePurchaseService).markFailed(42L);
        verifyNoInteractions(packOrderService);
    }

    @Test
    void handleWebhook_unknownPrefix_isAcknowledgedAndProcessedByNobody() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment stubbedPayment = paymentWithStatus("curso:42", "approved");
        when(mercadoPagoService.getPayment("555")).thenReturn(stubbedPayment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(challengePurchaseService, packOrderService);
    }

    @Test
    void handleWebhook_malformedPackReference_isAcknowledgedAndProcessedByNobody() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment stubbedPayment = paymentWithStatus("pack:abc", "approved");
        when(mercadoPagoService.getPayment("555")).thenReturn(stubbedPayment);

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(challengePurchaseService, packOrderService);
    }

    @Test
    void handleWebhook_packOrderThatDoesNotExist_isAcknowledgedWithoutRetry() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment stubbedPayment = packPayment("pack:999", "approved");
        when(mercadoPagoService.getPayment("555")).thenReturn(stubbedPayment);
        doThrow(new ResourceNotFoundException("Pack order not found: 999"))
                .when(packOrderService).onPayment(org.mockito.ArgumentMatchers.eq(999L), org.mockito.ArgumentMatchers.any());

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void handleWebhook_packOrderServiceFailure_returns5xxSoMercadoPagoRetries() throws Exception {
        givenNoSignatureCheck();
        when(mercadoPagoService.isConfigured()).thenReturn(true);
        Payment stubbedPayment = packPayment("pack:42", "approved");
        when(mercadoPagoService.getPayment("555")).thenReturn(stubbedPayment);
        doThrow(new IllegalStateException("db down"))
                .when(packOrderService).onPayment(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any());

        ResponseEntity<Void> response =
                controller.handleWebhook(null, "payment", null, "555", null, new MockHttpServletRequest());

        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
    }

    @Test
    void handleWebhook_packPaymentWithAnInvalidSignature_isRejectedBeforeAnythingIsRead() {
        when(mercadoPagoProperties.getWebhookSecret()).thenReturn(SECRET);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-signature", "ts=1704908010,v1=deadbeef");
        request.addHeader("x-request-id", REQUEST_ID);

        ResponseEntity<Void> response = controller.handleWebhook(null, "payment", null, "555", null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(packOrderService, challengePurchaseService, mercadoPagoService);
    }

    // --- fixtures -------------------------------------------------------------

    /** Secret unset -> isValidSignature short-circuits to true regardless of headers. */
    private void givenNoSignatureCheck() {
        when(mercadoPagoProperties.getWebhookSecret()).thenReturn(null);
    }

    private Payment approvedPayment(String externalReference) {
        return paymentWithStatus(externalReference, "approved");
    }

    private Payment packPayment(String externalReference, String status) {
        Payment payment = mock(Payment.class);
        when(payment.getExternalReference()).thenReturn(externalReference);
        when(payment.getStatus()).thenReturn(status);
        when(payment.getTransactionAmount()).thenReturn(new java.math.BigDecimal("14990.00"));
        when(payment.getCurrencyId()).thenReturn("ARS");
        return payment;
    }

    private Payment paymentWithStatus(String externalReference, String status) {
        Payment payment = mock(Payment.class);
        when(payment.getExternalReference()).thenReturn(externalReference);
        when(payment.getStatus()).thenReturn(status);
        return payment;
    }

    private MockHttpServletRequest signedRequest(String resourceId, String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String manifest = "id:" + resourceId.toLowerCase() + ";request-id:" + REQUEST_ID + ";ts:" + ts + ";";
        request.addHeader("x-signature", "ts=" + ts + ",v1=" + hmacSha256Hex(manifest, secret));
        request.addHeader("x-request-id", REQUEST_ID);
        return request;
    }

    private static String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
