package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.subscription.service.CreatedPreference;
import com.tiam.subscription.service.MercadoPagoService;
import com.tiam.testsupport.MutableClock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The 15-minute "double click" reuse must only hand back a checkout that was started for the SAME buyer details: a
 * different name (or, with the Desafío add-on, a different phone) is a different purchase and gets its own order.
 * Both products share the rule.
 */
@ExtendWith(MockitoExtension.class)
class PackCheckoutReuseKeyTest {

    static final String FIRST = "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=PREF-1";
    static final String SECOND = "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=PREF-2";

    @Mock PackOrderRepository orders;
    @Mock MercadoPagoService mercadoPago;
    @Mock ChallengePurchaseService challengePurchases;

    PackProperties properties;
    PackCheckoutService service;
    long nextId = 40;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.setSalesEnabled(true);
        properties.getCuadernos().setSalesEnabled(true);
        properties.setLandingBaseUrl("https://tiamdigital.com");
        properties.setThankyouUrl("https://tiamdigital.com/pack/gracias");
        properties.getRateLimits().setPendingPerEmailAndIp(50); // these tests start many checkouts for one buyer
        properties.getRateLimits().setCheckoutPerIp(100);
        service = new PackCheckoutService(orders, new PackPricing(properties), properties, mercadoPago, challengePurchases,
                new PackRateLimiter(new MutableClock(Instant.parse("2026-10-14T12:00:00Z"))),
                org.mockito.Mockito.mock(PackSalesReadiness.class));
    }

    @Test
    void sameBuyerSameDetails_reusesTheCheckout_forEveryEdition() throws Exception {
        for (String edition : List.of("familias", "profesionales", "cuadernos")) {
            PackOrder created = firstCheckout(edition, "Ana Gómez", List.of(), null);
            givenReusable(created);

            String redirect = service.start(command(edition, "Ana Gómez", List.of(), null));

            assertThat(redirect).as(edition).isEqualTo(FIRST);
        }
        verify(mercadoPago, times(3)).createCheckoutPreference(any()); // one per edition, none for the second clicks
    }

    @Test
    void aDifferentName_isADifferentPurchase_andGetsANewOrder() throws Exception {
        for (String edition : List.of("familias", "cuadernos")) {
            PackOrder created = firstCheckout(edition, "Ana Gómez", List.of(), null);
            givenReusable(created);
            givenNextPreference(SECOND);

            String redirect = service.start(command(edition, "Beatriz Gómez", List.of(), null));

            assertThat(redirect).as(edition).isEqualTo(SECOND);
        }
        verify(mercadoPago, times(4)).createCheckoutPreference(any());
        ArgumentCaptor<PackOrder> saved = ArgumentCaptor.forClass(PackOrder.class);
        verify(orders, atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(PackOrder::getBuyerName).contains("Beatriz Gómez");
    }

    @Test
    void withTheDesafioAddOn_aDifferentPhone_isANewOrder() throws Exception {
        for (String edition : List.of("familias", "cuadernos")) {
            when(challengePurchases.hasMonthAvailable(any())).thenReturn(true);
            PackOrder created = firstCheckout(edition, "Ana Gómez", List.of("juegos"), "221 555-0100");
            givenReusable(created);
            givenNextPreference(SECOND);

            String redirect = service.start(command(edition, "Ana Gómez", List.of("juegos"), "221 555-0199"));

            assertThat(redirect).as(edition).isEqualTo(SECOND);
        }
    }

    @Test
    void withTheDesafioAddOn_theSamePhone_isReused_evenWrittenAnotherWay() throws Exception {
        when(challengePurchases.hasMonthAvailable(any())).thenReturn(true);
        PackOrder created = firstCheckout("cuadernos", "Ana Gómez", List.of("juegos"), "221 555-0100");
        givenReusable(created);

        String redirect = service.start(command("cuadernos", "Ana Gómez", List.of("juegos"), "(221) 555-0100"));

        assertThat(redirect).isEqualTo(FIRST);
    }

    @Test
    void withoutTheDesafioAddOn_thePhoneIsNotPartOfTheKey() throws Exception {
        PackOrder created = firstCheckout("cuadernos", "Ana Gómez", List.of(), "221 555-0100");
        givenReusable(created);

        String redirect = service.start(command("cuadernos", "Ana Gómez", List.of(), null));

        assertThat(redirect).isEqualTo(FIRST);
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private PackOrder firstCheckout(String edition, String name, List<String> extras, String phone) throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.save(any(PackOrder.class))).thenAnswer(invocation -> {
            PackOrder order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(++nextId);
            }
            return order;
        });
        givenNextPreference(FIRST);
        when(orders.findReusable(any(), any(), anyBoolean(), anyBoolean(), any(), any())).thenReturn(List.of()); // a fresh buyer
        service.start(command(edition, name, extras, phone));
        ArgumentCaptor<PackOrder> captor = ArgumentCaptor.forClass(PackOrder.class);
        verify(orders, atLeastOnce()).save(captor.capture());
        PackOrder created = captor.getAllValues().get(captor.getAllValues().size() - 1);
        org.mockito.Mockito.clearInvocations(orders);
        return created;
    }

    private void givenReusable(PackOrder created) {
        when(orders.findReusable(any(), any(), anyBoolean(), anyBoolean(), any(), any())).thenReturn(List.of(created));
    }

    private void givenNextPreference(String initPoint) throws Exception {
        org.mockito.Mockito.doReturn(new CreatedPreference("PREF", initPoint)).when(mercadoPago).createCheckoutPreference(any());
    }

    private static CheckoutCommand command(String edition, String name, List<String> extras, String phone) {
        return new CheckoutCommand("ana@example.com", name, edition, extras, phone, null, null, null, null, null, "1.2.3.4");
    }
}
