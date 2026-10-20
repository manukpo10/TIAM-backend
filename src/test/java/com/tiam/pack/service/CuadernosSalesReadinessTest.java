package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.pack.storage.PrivateFileStore;
import com.tiam.subscription.service.MercadoPagoService;
import com.tiam.testsupport.MutableClock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Fail closed: a Cuadernos checkout is refused (as "closed", before any order exists) until R2, a real email sender
 * and public https URLs are in place, exactly like the Pack's.
 */
class CuadernosSalesReadinessTest {

    PackProperties properties;
    PrivateFileStore store;
    EmailSender email;
    MockEnvironment environment;
    PackOrderRepository orders;
    MercadoPagoService mercadoPago;
    ChallengePurchaseService challengePurchases;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.getCuadernos().setSalesEnabled(true);
        properties.setLandingBaseUrl("https://tiamdigital.com");
        properties.setThankyouUrl("https://tiamdigital.com/pack/gracias");
        properties.setDownloadBaseUrl("https://tiamdigital.com/pack/descargas");
        properties.setOwnerNotifyTo("owner@example.com");
        store = mock(PrivateFileStore.class);
        when(store.isRemote()).thenReturn(true);
        email = mock(EmailSender.class);
        when(email.isEnabled()).thenReturn(true);
        environment = new MockEnvironment();
        orders = mock(PackOrderRepository.class);
        mercadoPago = mock(MercadoPagoService.class);
        challengePurchases = mock(ChallengePurchaseService.class);
    }

    @Test
    void withEverythingInPlace_theShopIsReady() {
        assertThat(readiness().problems()).isEmpty();
    }

    @Test
    void aCuadernosCheckout_withTheLocalFileStore_isRefusedAsClosed_beforeAnyOrder() {
        when(store.isRemote()).thenReturn(false);

        assertClosed();
    }

    @Test
    void aCuadernosCheckout_withoutRealEmail_isRefusedAsClosed() {
        when(email.isEnabled()).thenReturn(false);

        assertClosed();
    }

    @Test
    void aCuadernosCheckout_withLocalhostUrls_isRefusedAsClosed() {
        properties.setThankyouUrl("http://localhost:5173/pack/gracias");

        assertClosed();
    }

    @Test
    void aCuadernosCheckout_withNobodyToNotify_isRefusedAsClosed() {
        properties.setOwnerNotifyTo("");
        properties.setRegretNotifyTo("");

        assertClosed();
    }

    @Test
    void aCuadernosCheckout_withFilesMissingInR2_isRefusedAsClosed_whileThePackStaysOpen() throws Exception {
        properties.setSalesEnabled(true);
        // R2 has the Pack's files but one of the Cuadernos' is not uploaded yet
        when(store.missingKeys(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<String> keys = invocation.getArgument(0);
            return keys.stream().filter(key -> key.endsWith("cuadernos/a4/cuaderno-2.pdf")).toList();
        });
        org.mockito.Mockito.when(mercadoPago.isConfigured()).thenReturn(true);
        org.mockito.Mockito.when(orders.save(org.mockito.ArgumentMatchers.any(com.tiam.pack.domain.PackOrder.class)))
                .thenAnswer(invocation -> {
                    com.tiam.pack.domain.PackOrder order = invocation.getArgument(0);
                    order.setId(42L);
                    return order;
                });
        org.mockito.Mockito.when(mercadoPago.createCheckoutPreference(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.tiam.subscription.service.CreatedPreference("PREF",
                        "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=PREF"));
        PackCheckoutService service = service();

        assertThatThrownBy(() -> service.start(command("cuadernos")))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.CERRADO));
        verifyNoInteractions(mercadoPago);

        assertThat(service.start(command("familias"))).contains("mercadopago.com.ar"); // the Pack is not affected
    }

    @Test
    void aCuadernosCheckout_withAPriceBelowAThousand_isRefusedAsClosed() {
        properties.getCuadernos().setPriceArs(new java.math.BigDecimal("20")); // "20.000" typed with a dot

        assertClosed();
    }

    @Test
    void devMode_liftsTheRequirementsOutsideProduction_andNeverInProduction() {
        when(store.isRemote()).thenReturn(false);
        properties.setDevMode(true);
        assertThat(readiness().problems()).isEmpty();

        environment.setActiveProfiles("prod");
        assertThat(readiness().problems()).isNotEmpty();
    }

    private PackCheckoutService service() {
        return new PackCheckoutService(orders, new PackPricing(properties), properties, mercadoPago,
                challengePurchases, new PackRateLimiter(new MutableClock(Instant.parse("2026-10-14T12:00:00Z"))), readiness());
    }

    private static CheckoutCommand command(String edition) {
        return new CheckoutCommand("ana@example.com", "Ana Gómez", edition, List.of(), null, null,
                null, null, null, null, "1.2.3.4");
    }

    private void assertClosed() {
        PackCheckoutService service = service();
        CheckoutCommand command = command("cuadernos");

        assertThatThrownBy(() -> service.start(command))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.CERRADO));

        verifyNoInteractions(orders, mercadoPago, challengePurchases);
    }

    private PackSalesReadiness readiness() {
        return new PackSalesReadiness(properties, store, email, environment);
    }
}
