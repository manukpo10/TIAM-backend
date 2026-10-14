package com.tiam.pack.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.payment.Payment;
import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.subscription.service.MercadoPagoService;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Lets the buyer's return page get its files without waiting for the webhook: it hands over the
 * {@code payment_id} Mercado Pago appended to the return URL, and this service goes and ASKS Mercado Pago
 * about that payment. The browser's word is never taken: the payment is re-read from Mercado Pago, it must
 * carry this order's own {@code external_reference}, and it then goes through the same
 * {@link PackOrderService#onPayment} (same amount and currency checks, same idempotency) as the webhook.
 *
 * <p>Not transactional: it makes a network call to Mercado Pago, which must not hold a database connection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PackPaymentConfirmationService {

    private static final Pattern PAYMENT_ID = Pattern.compile("\\d{1,18}");

    private final PackOrderRepository orders;
    private final MercadoPagoService mercadoPago;
    private final PackOrderService packOrderService;

    /** @return the order status after applying what Mercado Pago says about that payment */
    public PackOrderStatus confirm(String token, String paymentId) {
        PackOrder order = findOrder(token);
        if (order.getStatus() == PackOrderStatus.PAID) {
            return PackOrderStatus.PAID;
        }
        if (paymentId == null || !PAYMENT_ID.matcher(paymentId).matches()) {
            throw new BadRequestException("paymentId must be a number");
        }
        if (!mercadoPago.isConfigured()) {
            throw new PackUnavailableException("Payments are not available right now");
        }

        Payment payment = fetchPayment(paymentId);
        if (!order.externalReference().equals(payment.getExternalReference())) {
            log.warn("Pack confirm: payment {} does not belong to order id={}", paymentId, order.getId());
            throw new BadRequestException("The payment does not belong to this order");
        }
        return packOrderService.onPayment(order.getId(), PaymentSnapshot.from(paymentId, payment));
    }

    private PackOrder findOrder(String token) {
        // Same uniform 404 for "bad shape", "unknown" and "revoked": nothing to tell an enumerator.
        if (!PackTokens.looksValid(token)) {
            throw new ResourceNotFoundException("Order not found");
        }
        PackOrder order = orders.findByAccessTokenAndActivoTrue(token)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getStatus() == PackOrderStatus.REVOKED) {
            throw new ResourceNotFoundException("Order not found");
        }
        return order;
    }

    private Payment fetchPayment(String paymentId) {
        try {
            return mercadoPago.getPayment(paymentId);
        } catch (MPApiException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND.value()) {
                throw new BadRequestException("Mercado Pago does not know that payment");
            }
            log.error("Pack confirm: Mercado Pago error {} fetching payment {}", e.getStatusCode(), paymentId);
            throw new PackUnavailableException("Could not check the payment right now", e);
        } catch (MPException e) {
            log.error("Pack confirm: could not fetch payment {}: {}", paymentId, e.getMessage());
            throw new PackUnavailableException("Could not check the payment right now", e);
        }
    }
}
