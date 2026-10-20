package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.config.PackSalesReadiness;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.subscription.service.CheckoutPreferenceSpec;
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

/** Starting a «Cuadernos TIAM» purchase through the same checkout the Pack uses. */
@ExtendWith(MockitoExtension.class)
class CuadernosCheckoutServiceTest {

    static final String INIT_POINT = "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=PREF-C1";

    @Mock PackOrderRepository orders;
    @Mock MercadoPagoService mercadoPago;
    @Mock ChallengePurchaseService challengePurchases;

    PackProperties properties;
    PackSalesReadiness readiness;
    PackCheckoutService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.getCuadernos().setSalesEnabled(true); // the Pack stays OFF: the switches are independent
        properties.setLandingBaseUrl("https://tiamdigital.com");
        properties.setThankyouUrl("https://tiamdigital.com/pack/gracias");
        MutableClock clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        readiness = org.mockito.Mockito.mock(PackSalesReadiness.class); // ready: problems() is empty
        service = new PackCheckoutService(orders, new PackPricing(properties), properties, mercadoPago,
                challengePurchases, new PackRateLimiter(clock), readiness);
    }

    // --- the happy path ---------------------------------------------------------------------------------------

    @Test
    void start_createsAPendingCuadernosOrderWithTheServerPrice_whileThePackIsNotForSale() throws Exception {
        givenMercadoPagoWorks();
        assertThat(properties.isSalesEnabled()).isFalse();

        String redirect = service.start(command().build());

        assertThat(redirect).isEqualTo(INIT_POINT);
        PackOrder order = firstSavedOrder();
        assertThat(order.getStatus()).isEqualTo(PackOrderStatus.PENDING);
        assertThat(order.getEdition()).isEqualTo(PackEdition.CUADERNOS);
        assertThat(order.getPackAmount()).isEqualByComparingTo("20000");
        assertThat(order.getGamesAmount()).isEqualByComparingTo("0");
        assertThat(order.getExtraAmount()).isEqualByComparingTo("0");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("20000");
        assertThat(order.isWithExtra()).isFalse();
        assertThat(order.getCurrency()).isEqualTo("ARS");
        assertThat(order.getCatalogVersion()).isEqualTo("v1");
    }

    @Test
    void start_theOrderRemembersTheCuadernosCatalogVersion_notThePacks() throws Exception {
        properties.setCatalogVersion("v7");              // bumping the Pack's catalog ...
        properties.getCuadernos().setCatalogVersion("v1"); // ... must never move the Cuadernos' files
        givenMercadoPagoWorks();

        service.start(command().build());

        assertThat(firstSavedOrder().getCatalogVersion()).isEqualTo("v1");
    }

    @Test
    void start_aNewCuadernosCatalogVersion_appliesOnlyToNewCuadernosOrders() throws Exception {
        properties.getCuadernos().setCatalogVersion("v2");
        properties.setSalesEnabled(true);
        givenMercadoPagoWorks();

        service.start(command().email("a@example.com").edition("cuadernos").build());
        service.start(command().email("b@example.com").edition("familias").build());

        ArgumentCaptor<PackOrder> captor = ArgumentCaptor.forClass(PackOrder.class);
        verify(orders, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(o -> o.getEdition() == PackEdition.CUADERNOS)
                .allSatisfy(o -> assertThat(o.getCatalogVersion()).isEqualTo("v2"));
        assertThat(captor.getAllValues()).filteredOn(o -> o.getEdition() == PackEdition.FAMILIAS)
                .allSatisfy(o -> assertThat(o.getCatalogVersion()).isEqualTo("v1")); // the Pack keeps its own setting
    }

    @Test
    void start_chargesTheConfiguredCuadernosPrice() throws Exception {
        properties.getCuadernos().setPriceArs(new java.math.BigDecimal("17990"));
        givenMercadoPagoWorks();

        service.start(command().build());

        assertThat(firstSavedOrder().getTotalAmount()).isEqualByComparingTo("17990");
    }

    @Test
    void start_sendsMercadoPagoTheCuadernosLine_withTheUsualReferenceAndReturnUrl() throws Exception {
        givenMercadoPagoWorks();

        service.start(command().build());

        CheckoutPreferenceSpec spec = capturedSpec();
        assertThat(spec.lines()).hasSize(1);
        assertThat(spec.lines()).extracting("id").containsExactly("cuadernos");
        assertThat(spec.lines().get(0).title()).contains("Cuadernos TIAM").doesNotContain("Pack").doesNotContain("Edición");
        assertThat(spec.lines().get(0).unitPrice()).isEqualByComparingTo("20000");
        assertThat(spec.externalReference()).isEqualTo("pack:42");
        assertThat(spec.backUrl()).isEqualTo("https://tiamdigital.com/pack/gracias?o=" + firstSavedOrder().getAccessToken());
        assertThat(spec.statementDescriptor()).isEqualTo("TIAM");
    }

    @Test
    void start_withTheGamesMonth_needsAPhone_andAddsTheGamesLine() throws Exception {
        givenMercadoPagoWorks();
        when(challengePurchases.hasMonthAvailable("542211234567")).thenReturn(true);

        service.start(command().extras(List.of("juegos")).phone("221 123-4567").expectedTotal("35000").build());

        PackOrder order = firstSavedOrder();
        assertThat(order.isWithGames()).isTrue();
        assertThat(order.getPackAmount()).isEqualByComparingTo("20000");
        assertThat(order.getGamesAmount()).isEqualByComparingTo("15000");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("35000");
        assertThat(order.getPhone()).isEqualTo("542211234567");
        CheckoutPreferenceSpec spec = capturedSpec();
        assertThat(spec.lines()).extracting("id").containsExactly("cuadernos", "juegos");
        assertThat(spec.lines().get(1).unitPrice()).isEqualByComparingTo("15000");
        // neutral: the month granted is the buyer's next unpaid one, which is not always the first
        assertThat(spec.lines().get(1).description()).contains("1 mes del Desafío").contains("sin renovación automática")
                .doesNotContainIgnoringCase("primer mes");
    }

    @Test
    void start_theGamesOfferIsExactlyOneMonth_theOldGamesTotalIsNoLongerAccepted() {
        assertThatThrownBy(() -> service.start(command().extras(List.of("juegos")).phone("221 123-4567")
                .expectedTotal("30000").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.PRECIO));

        verify(orders, never()).save(any());
    }

    @Test
    void start_thePacksGamesLineKeepsItsPriceAndWording() throws Exception {
        properties.setSalesEnabled(true);
        givenMercadoPagoWorks();
        when(challengePurchases.hasMonthAvailable(any())).thenReturn(true);

        service.start(command().edition("familias").extras(List.of("juegos")).phone("221 123-4567").build());

        CheckoutPreferenceSpec spec = capturedSpec();
        assertThat(spec.lines().get(1).unitPrice()).isEqualByComparingTo("10000");
        assertThat(spec.lines().get(1).description()).isEqualTo("Un mes del Desafío 30 días de TIAM Digital");
        assertThat(firstSavedOrder().getTotalAmount()).isEqualByComparingTo("24990");
    }

    @Test
    void start_gamesWithoutAPhone_isRefused() {
        assertThatThrownBy(() -> service.start(command().extras(List.of("juegos")).phone(null).build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.TELEFONO));

        verify(orders, never()).save(any());
    }

    @Test
    void start_gamesWhenThePhoneHasEveryMonth_isRefusedWithTheMonthsError() {
        when(challengePurchases.hasMonthAvailable(any())).thenReturn(false);

        assertThatThrownBy(() -> service.start(command().extras(List.of("juegos")).phone("221 123-4567").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.MESES));
    }

    // --- the cuadernillo extra does not apply -----------------------------------------------------------------

    @Test
    void start_theCuadernilloExtra_isRejectedWithTheExistingExtrasError_andNothingIsCreated() {
        assertThatThrownBy(() -> service.start(command().extras(List.of("cuadernillo")).build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EXTRAS));

        verifyNoInteractions(mercadoPago, challengePurchases);
        verify(orders, never()).save(any());
    }

    @Test
    void start_theCuadernilloExtraWithGames_isRejectedBeforeAskingForThePhone() {
        assertThatThrownBy(() -> service.start(command().extras(List.of("juegos", "cuadernillo")).phone(null).build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EXTRAS));

        verifyNoInteractions(challengePurchases);
    }

    @Test
    void start_anUnknownExtra_isStillRefused() {
        assertThatThrownBy(() -> service.start(command().extras(List.of("regalo")).build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EXTRAS));
    }

    // --- the browser never decides the price -------------------------------------------------------------------

    @Test
    void start_anExpectedTotalOfThePacksPrice_orOfTheGamesPriceWithoutGames_isRefused() {
        properties.getCuadernos().setPriceArs(new java.math.BigDecimal("17990"));

        assertThatThrownBy(() -> service.start(command().expectedTotal("14990").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.PRECIO));
        assertThatThrownBy(() -> service.start(command().expectedTotal("27990").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.PRECIO));

        verify(orders, never()).save(any());
    }

    @Test
    void start_anExpectedTotalThatMatches_isAccepted() throws Exception {
        givenMercadoPagoWorks();

        service.start(command().expectedTotal("20000,00").build());

        verify(mercadoPago).createCheckoutPreference(any());
    }

    // --- the sales switches are independent ----------------------------------------------------------------------

    @Test
    void start_cuadernosSwitchedOff_isClosed_evenWithThePackOn() {
        properties.getCuadernos().setSalesEnabled(false);
        properties.setSalesEnabled(true);

        assertThatThrownBy(() -> service.start(command().build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.CERRADO));

        verifyNoInteractions(orders, mercadoPago, challengePurchases);
    }

    @Test
    void start_packSwitchedOff_isClosedForThePack_butNotForTheCuadernos() throws Exception {
        givenMercadoPagoWorks();

        assertThatThrownBy(() -> service.start(command().edition("familias").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.CERRADO));
        assertThatThrownBy(() -> service.start(command().edition("profesionales").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.CERRADO));
        verify(orders, never()).save(any());

        assertThat(service.start(command().build())).isEqualTo(INIT_POINT);
    }

    @Test
    void start_bothOn_eachEditionSellsAtItsOwnPrice() throws Exception {
        properties.setSalesEnabled(true);
        givenMercadoPagoWorks();

        service.start(command().email("a@example.com").edition("familias").build());
        service.start(command().email("b@example.com").edition("cuadernos").build());

        ArgumentCaptor<PackOrder> captor = ArgumentCaptor.forClass(PackOrder.class);
        verify(orders, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(PackOrder::getEdition)
                .contains(PackEdition.FAMILIAS, PackEdition.CUADERNOS);
    }

    @Test
    void start_anUnknownEdition_isClosedWhileThePackIsOff_andRefusedOnceItIsOn() {
        // unchanged behaviour: the gate for anything that is not the Cuadernos is the Pack's switch
        assertThatThrownBy(() -> service.start(command().edition("otra").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.CERRADO));

        properties.setSalesEnabled(true);
        assertThatThrownBy(() -> service.start(command().edition("otra").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EDICION));
    }

    @Test
    void start_withTheShopNotReady_refusesAsClosedBeforeTouchingAnything() {
        when(readiness.problems()).thenReturn(List.of("the private file store is not Cloudflare R2"));

        assertThatThrownBy(() -> service.start(command().build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.CERRADO));

        verifyNoInteractions(orders, mercadoPago, challengePurchases);
    }

    @Test
    void start_aMisconfiguredCuadernosPrice_neverCreatesAnOrder() {
        properties.getCuadernos().setPriceArs(java.math.BigDecimal.ZERO);

        assertThatThrownBy(() -> service.start(command().build())).isInstanceOf(IllegalStateException.class);

        verify(orders, never()).save(any());
    }

    // --- double click ----------------------------------------------------------------------------------------------

    @Test
    void start_aDoubleClickReusesTheCuadernosCheckout_withoutCreatingAnotherOne() throws Exception {
        givenMercadoPagoWorks();
        service.start(command().build());
        PackOrder created = lastSavedOrder();
        when(orders.findReusable(eq("ana@example.com"), eq(PackEdition.CUADERNOS), eq(false), eq(false),
                eq(PackOrderStatus.PENDING), any(Instant.class))).thenReturn(List.of(created));

        String redirect = service.start(command().build());

        assertThat(redirect).isEqualTo(INIT_POINT);
        verify(mercadoPago, org.mockito.Mockito.times(1)).createCheckoutPreference(any());
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private void givenMercadoPagoWorks() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.save(any(PackOrder.class))).thenAnswer(invocation -> {
            PackOrder order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(42L);
            }
            return order;
        });
        when(mercadoPago.createCheckoutPreference(any())).thenReturn(new CreatedPreference("PREF-C1", INIT_POINT));
    }

    private PackOrder firstSavedOrder() {
        ArgumentCaptor<PackOrder> captor = ArgumentCaptor.forClass(PackOrder.class);
        verify(orders, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(0);
    }

    private PackOrder lastSavedOrder() {
        ArgumentCaptor<PackOrder> captor = ArgumentCaptor.forClass(PackOrder.class);
        verify(orders, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    private CheckoutPreferenceSpec capturedSpec() throws Exception {
        ArgumentCaptor<CheckoutPreferenceSpec> captor = ArgumentCaptor.forClass(CheckoutPreferenceSpec.class);
        verify(mercadoPago).createCheckoutPreference(captor.capture());
        return captor.getValue();
    }

    private static CommandBuilder command() {
        return new CommandBuilder();
    }

    /** Valid defaults for a Cuadernos purchase; each test overrides only what it is about. */
    static final class CommandBuilder {
        private String email = "ana@example.com";
        private String edition = "cuadernos";
        private List<String> extras = List.of();
        private String phone = null;
        private String expectedTotal = null;

        CommandBuilder email(String v) { this.email = v; return this; }
        CommandBuilder edition(String v) { this.edition = v; return this; }
        CommandBuilder extras(List<String> v) { this.extras = v; return this; }
        CommandBuilder phone(String v) { this.phone = v; return this; }
        CommandBuilder expectedTotal(String v) { this.expectedTotal = v; return this; }

        CheckoutCommand build() {
            return new CheckoutCommand(email, "Ana Gómez", edition, extras, phone, expectedTotal,
                    null, null, null, null, "1.2.3.4");
        }
    }
}
