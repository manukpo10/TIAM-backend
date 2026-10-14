package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.net.MPResponse;
import com.mercadopago.resources.payment.Payment;
import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.subscription.service.MercadoPagoService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackPaymentConfirmationServiceTest {

    final String token = PackTokens.newToken();

    @Mock PackOrderRepository orders;
    @Mock MercadoPagoService mercadoPago;
    @Mock PackOrderService packOrderService;

    PackPaymentConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new PackPaymentConfirmationService(orders, mercadoPago, packOrderService);
    }

    @Test
    void confirm_rereadsThePaymentFromMercadoPago_checksItBelongsToTheOrder_andAppliesIt() throws Exception {
        PackOrder order = order(PackOrderStatus.PENDING);
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order));
        when(mercadoPago.isConfigured()).thenReturn(true);
        Payment payment = payment("pack:42", "approved");
        when(mercadoPago.getPayment("987654")).thenReturn(payment);
        when(packOrderService.onPayment(eq(42L), any())).thenReturn(PackOrderStatus.PAID);

        PackOrderStatus status = service.confirm(token, "987654");

        assertThat(status).isEqualTo(PackOrderStatus.PAID);
        ArgumentCaptor<PaymentSnapshot> snapshot = ArgumentCaptor.forClass(PaymentSnapshot.class);
        verify(packOrderService).onPayment(eq(42L), snapshot.capture());
        assertThat(snapshot.getValue().paymentId()).isEqualTo("987654");
        assertThat(snapshot.getValue().status()).isEqualTo("approved");
        assertThat(snapshot.getValue().amount()).isEqualByComparingTo("14990.00");
    }

    @Test
    void confirm_aPaymentOfAnotherOrder_isRefused_andNothingIsApplied() throws Exception {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order(PackOrderStatus.PENDING)));
        when(mercadoPago.isConfigured()).thenReturn(true);
        Payment payment = payment("pack:43", "approved");
        when(mercadoPago.getPayment("987654")).thenReturn(payment);

        assertThatThrownBy(() -> service.confirm(token, "987654")).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(packOrderService);
    }

    @Test
    void confirm_aDesafioPaymentCannotBeUsedToPayAPackOrder() throws Exception {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order(PackOrderStatus.PENDING)));
        when(mercadoPago.isConfigured()).thenReturn(true);
        Payment payment = payment("42", "approved"); // same number, but a Desafio reference
        when(mercadoPago.getPayment("987654")).thenReturn(payment);

        assertThatThrownBy(() -> service.confirm(token, "987654")).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(packOrderService);
    }

    @Test
    void confirm_anAlreadyPaidOrder_answersWithoutCallingMercadoPago() {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order(PackOrderStatus.PAID)));

        assertThat(service.confirm(token, "987654")).isEqualTo(PackOrderStatus.PAID);

        verifyNoInteractions(mercadoPago, packOrderService);
    }

    @Test
    void confirm_unknownToken_isNotFound() {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(token, "987654")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirm_aMalformedToken_isNotFound_withoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.confirm("nope", "987654")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.confirm(null, "987654")).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(orders);
    }

    @Test
    void confirm_aRevokedOrder_isNotFound() {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order(PackOrderStatus.REVOKED)));

        assertThatThrownBy(() -> service.confirm(token, "987654")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void confirm_aNonNumericPaymentId_isABadRequest_andNeverReachesMercadoPago() {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order(PackOrderStatus.PENDING)));

        assertThatThrownBy(() -> service.confirm(token, "../../etc/passwd")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.confirm(token, "")).isInstanceOf(BadRequestException.class);

        verifyNoInteractions(mercadoPago, packOrderService);
    }

    @Test
    void confirm_mercadoPagoNotConfigured_isUnavailable() {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order(PackOrderStatus.PENDING)));
        when(mercadoPago.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(token, "987654")).isInstanceOf(PackUnavailableException.class);
    }

    @Test
    void confirm_mercadoPagoDown_isUnavailableSoTheReturnPageCanTryAgain() throws Exception {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order(PackOrderStatus.PENDING)));
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(mercadoPago.getPayment("987654")).thenThrow(new MPApiException("down", new MPResponse(503, null, null)));

        assertThatThrownBy(() -> service.confirm(token, "987654")).isInstanceOf(PackUnavailableException.class);

        verify(packOrderService, never()).onPayment(any(), any());
    }

    @Test
    void confirm_aPaymentIdMercadoPagoDoesNotKnow_isABadRequest() throws Exception {
        when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order(PackOrderStatus.PENDING)));
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(mercadoPago.getPayment("987654")).thenThrow(new MPApiException("not found", new MPResponse(404, null, null)));

        assertThatThrownBy(() -> service.confirm(token, "987654")).isInstanceOf(BadRequestException.class);
    }

    private PackOrder order(PackOrderStatus status) {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setStatus(status);
        order.setAccessToken(token);
        return order;
    }

    private static Payment payment(String externalReference, String status) {
        Payment payment = mock(Payment.class);
        lenient().when(payment.getExternalReference()).thenReturn(externalReference);
        lenient().when(payment.getStatus()).thenReturn(status);
        lenient().when(payment.getTransactionAmount()).thenReturn(new BigDecimal("14990.00"));
        lenient().when(payment.getCurrencyId()).thenReturn("ARS");
        return payment;
    }
}
