package com.tiam.subscription.service;

import com.mercadopago.net.MPSearchRequest;
import com.mercadopago.net.UrlFormatter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression guard for the exact {@link MPSearchRequest} shape used by
 * {@link MercadoPagoService#findLatestPaymentByExternalReference}.
 *
 * <p>This exercises the REAL Mercado Pago SDK classes (no mocking) because the bug it
 * guards against lives entirely inside the SDK's own request-building code:
 * {@code MPSearchRequest.getParameters()} unconditionally inserts "limit" and "offset"
 * keys into the params map — even when never set on the builder, in which case it
 * inserts {@code null} — and {@code UrlFormatter.format()} calls {@code toString()} on
 * every param value with no null guard. Building a search request without explicit
 * limit/offset therefore throws a {@link NullPointerException} the moment the request
 * is actually sent. A test that mocks {@link MercadoPagoService} itself would never
 * catch this — it has to go through the real SDK classes.
 */
class MercadoPagoServiceSearchRequestTest {

    @Test
    void searchRequestParameters_withExplicitLimitAndOffset_formatsWithoutNpe() throws Exception {
        MPSearchRequest searchRequest = MPSearchRequest.builder()
                .filters(Map.of("external_reference", "42"))
                .limit(10)
                .offset(0)
                .build();

        assertThatCode(() -> UrlFormatter.format("/v1/payments/search", searchRequest.getParameters()))
                .doesNotThrowAnyException();
    }

    @Test
    void searchRequestParameters_withoutExplicitLimitAndOffset_throwsNpe() {
        // Documents WHY findLatestPaymentByExternalReference must always set limit/offset:
        // MPSearchRequest.getParameters() inserts null for any key not set on the builder,
        // and UrlFormatter.format() has no null guard before calling toString() on values.
        MPSearchRequest searchRequest = MPSearchRequest.builder()
                .filters(Map.of("external_reference", "42"))
                .build();

        assertThatCode(() -> UrlFormatter.format("/v1/payments/search", searchRequest.getParameters()))
                .isInstanceOf(NullPointerException.class);
    }
}
