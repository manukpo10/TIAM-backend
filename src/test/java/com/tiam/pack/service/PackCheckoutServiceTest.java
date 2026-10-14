package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.net.MPResponse;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.pack.config.PackProperties;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackCheckoutServiceTest {

    static final String INIT_POINT = "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=PREF-1";

    @Mock PackOrderRepository orders;
    @Mock MercadoPagoService mercadoPago;
    @Mock ChallengePurchaseService challengePurchases;

    PackProperties properties;
    MutableClock clock;
    PackRateLimiter limiter;
    PackCheckoutService service;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        properties.setSalesEnabled(true);
        properties.setLandingBaseUrl("https://tiamdigital.com");
        properties.setThankyouUrl("https://tiamdigital.com/pack/gracias");
        clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        limiter = new PackRateLimiter(clock);
        service = new PackCheckoutService(orders, new PackPricing(properties), properties, mercadoPago,
                challengePurchases, limiter);
    }

    // --- the happy path ----------------------------------------------------------------

    @Test
    void start_createsAPendingOrderWithTheAmountsTheServerComputed() throws Exception {
        givenMercadoPagoWorks();

        String redirect = service.start(command().build());

        assertThat(redirect).isEqualTo(INIT_POINT);
        PackOrder first = firstSavedOrder();
        assertThat(first.getStatus()).isEqualTo(PackOrderStatus.PENDING);
        assertThat(first.getEdition()).isEqualTo(PackEdition.FAMILIAS);
        assertThat(first.getPackAmount()).isEqualByComparingTo("14990");
        assertThat(first.getGamesAmount()).isEqualByComparingTo("0");
        assertThat(first.getExtraAmount()).isEqualByComparingTo("0");
        assertThat(first.getTotalAmount()).isEqualByComparingTo("14990");
        assertThat(first.getCurrency()).isEqualTo("ARS");
        assertThat(first.getCatalogVersion()).isEqualTo("v1");
    }

    @Test
    void start_storesTheBuyerNormalised() throws Exception {
        givenMercadoPagoWorks();

        service.start(command().email("  Ana.Gomez@Example.COM ").name("  Ana   María  Gómez ").build());

        PackOrder order = firstSavedOrder();
        assertThat(order.getEmail()).isEqualTo("ana.gomez@example.com");
        assertThat(order.getBuyerName()).isEqualTo("Ana María Gómez");
        assertThat(order.getAccessToken()).hasSize(43);
    }

    @Test
    void start_savesThePreferenceIdAndInitPointSoADoubleClickCanReuseThem() throws Exception {
        givenMercadoPagoWorks();

        service.start(command().build());

        PackOrder last = lastSavedOrder();
        assertThat(last.getMpPreferenceId()).isEqualTo("PREF-1");
        assertThat(last.getInitPoint()).isEqualTo(INIT_POINT);
        assertThat(last.getStatus()).isEqualTo(PackOrderStatus.PENDING);
    }

    @Test
    void start_sendsMercadoPagoOneItemPerLineWithTheOrderReferenceAndTheTokenInTheReturnUrl() throws Exception {
        givenMercadoPagoWorks();
        when(challengePurchases.hasMonthAvailable("542211234567")).thenReturn(true);

        service.start(command().extras(List.of("juegos", "cuadernillo")).phone("221 123-4567").build());

        CheckoutPreferenceSpec spec = capturedSpec();
        assertThat(spec.lines()).extracting("id").containsExactly("pack-familias", "juegos", "cuadernillo-extra");
        assertThat(spec.lines()).extracting("unitPrice")
                .usingElementComparator(java.util.Comparator.comparing(o -> (java.math.BigDecimal) o))
                .containsExactly(new java.math.BigDecimal("14990"), new java.math.BigDecimal("10000"),
                        new java.math.BigDecimal("4990"));
        assertThat(spec.externalReference()).isEqualTo("pack:42");
        assertThat(spec.backUrl()).isEqualTo("https://tiamdigital.com/pack/gracias?o=" + firstSavedOrder().getAccessToken());
        assertThat(spec.payerEmail()).isEqualTo("ana@example.com");
        assertThat(spec.statementDescriptor()).isEqualTo("TIAM");
        assertThat(spec.expiresAt()).isAfter(spec.expiresFrom());
    }

    @Test
    void start_appendsTheTokenWithAmpersandWhenTheThankYouUrlAlreadyHasAQuery() throws Exception {
        properties.setThankyouUrl("https://tiamdigital.com/pack/gracias?src=mp");
        givenMercadoPagoWorks();

        service.start(command().build());

        assertThat(capturedSpec().backUrl()).startsWith("https://tiamdigital.com/pack/gracias?src=mp&o=");
    }

    @Test
    void start_withGamesAndExtra_storesEachAmountAndTheTotal() throws Exception {
        givenMercadoPagoWorks();
        when(challengePurchases.hasMonthAvailable(any())).thenReturn(true);

        service.start(command().extras(List.of("juegos", "cuadernillo")).phone("221 123-4567").build());

        PackOrder order = firstSavedOrder();
        assertThat(order.isWithGames()).isTrue();
        assertThat(order.isWithExtra()).isTrue();
        assertThat(order.getGamesAmount()).isEqualByComparingTo("10000");
        assertThat(order.getExtraAmount()).isEqualByComparingTo("4990");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("29980");
        assertThat(order.getPhone()).isEqualTo("542211234567");
    }

    @Test
    void start_keepsTheUtmFieldsButCapsTheirLength() throws Exception {
        givenMercadoPagoWorks();

        service.start(command().utmSource("facebook").utmCampaign("x".repeat(500)).utmMedium("cpc\n\r").build());

        PackOrder order = firstSavedOrder();
        assertThat(order.getUtmSource()).isEqualTo("facebook");
        assertThat(order.getUtmMedium()).isEqualTo("cpc");
        assertThat(order.getUtmCampaign()).hasSize(100);
        assertThat(order.getUtmContent()).isNull();
    }

    // --- the browser never decides the price -------------------------------------------

    @Test
    void start_anExpectedTotalThatDoesNotMatchTheServerPriceIsRefusedBeforeAnythingIsCreated() {
        assertThatThrownBy(() -> service.start(command().expectedTotal("100").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.PRECIO));

        verify(orders, never()).save(any());
        verifyNoInteractions(mercadoPago);
    }

    @Test
    void start_anExpectedTotalThatMatchesIsAccepted_inAnyOfTheCommonNumberFormats() throws Exception {
        givenMercadoPagoWorks();

        service.start(command().expectedTotal("14990").build());
        service.start(command().email("b@example.com").expectedTotal("14990.00").build());
        service.start(command().email("c@example.com").expectedTotal("14990,00").build());

        verify(mercadoPago, times(3)).createCheckoutPreference(any());
    }

    @Test
    void start_anExpectedTotalThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> service.start(command().expectedTotal("gratis").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.PRECIO));
    }

    @Test
    void start_withoutAnExpectedTotal_theServerPriceStillApplies() throws Exception {
        givenMercadoPagoWorks();

        service.start(command().expectedTotal(null).build());

        assertThat(firstSavedOrder().getTotalAmount()).isEqualByComparingTo("14990");
    }

    // --- double click --------------------------------------------------------------------

    @Test
    void start_aDoubleClickFromTheSameVisitorReusesTheirCheckout_withoutCreatingAnotherOne() throws Exception {
        givenMercadoPagoWorks();
        service.start(command().build()); // first click creates order 42 for this visitor's IP
        PackOrder created = lastSavedOrder();
        when(orders.findReusable(eq("ana@example.com"), eq(PackEdition.FAMILIAS), eq(false), eq(false),
                eq(PackOrderStatus.PENDING), any(Instant.class))).thenReturn(List.of(created));

        String redirect = service.start(command().build()); // second click, same visitor

        assertThat(redirect).isEqualTo(INIT_POINT);
        verify(mercadoPago, times(1)).createCheckoutPreference(any()); // no second preference
    }

    @Test
    void start_aCheckoutStartedByAnotherVisitorForTheSameEmail_isNeverReused() throws Exception {
        // Someone who knows a buyer's email must not be able to pre-create the checkout that buyer will land on
        // (it would carry the attacker's name, phone and order token).
        givenMercadoPagoWorks();
        service.start(command().clientIp("6.6.6.6").build()); // attacker
        PackOrder attackers = lastSavedOrder();
        when(orders.findReusable(any(), any(), anyBoolean(), anyBoolean(), any(), any())).thenReturn(List.of(attackers));
        when(mercadoPago.createCheckoutPreference(any())).thenReturn(new CreatedPreference("PREF-VICTIM",
                "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=PREF-VICTIM"));

        String redirect = service.start(command().clientIp("7.7.7.7").build()); // the real buyer

        assertThat(redirect).contains("PREF-VICTIM");
        verify(mercadoPago, times(2)).createCheckoutPreference(any());
    }

    @Test
    void start_aCheckoutThisProcessDoesNotRememberStarting_isNeverReused() throws Exception {
        // e.g. created before a restart: the safe direction is a fresh order, not a possible stranger's one
        PackOrder stranger = new PackOrder();
        stranger.setId(7L);
        stranger.setInitPoint("https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=PREV");
        when(orders.findReusable(any(), any(), anyBoolean(), anyBoolean(), any(), any())).thenReturn(List.of(stranger));
        givenMercadoPagoWorks();

        String redirect = service.start(command().build());

        assertThat(redirect).isEqualTo(INIT_POINT);
    }

    @Test
    void start_reusingACheckoutDoesNotConsumeTheIpQuota() throws Exception {
        properties.getRateLimits().setCheckoutPerIp(1);
        givenMercadoPagoWorks();
        service.start(command().build()); // uses the single allowed checkout
        PackOrder created = lastSavedOrder();
        when(orders.findReusable(any(), any(), anyBoolean(), anyBoolean(), any(), any())).thenReturn(List.of(created));

        for (int i = 0; i < 5; i++) {
            assertThat(service.start(command().build())).isEqualTo(INIT_POINT);
        }
    }

    // --- validation ------------------------------------------------------------------------

    @Test
    void start_salesSwitchedOff_isClosedAndNothingIsCreated() {
        properties.setSalesEnabled(false);

        assertThatThrownBy(() -> service.start(command().build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.CERRADO));

        verifyNoInteractions(orders, mercadoPago);
    }

    @Test
    void start_mercadoPagoNotConfigured_isClosed() {
        when(mercadoPago.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.start(command().build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.CERRADO));

        verify(orders, never()).save(any());
    }

    @Test
    void start_invalidEmails_areRefused() {
        for (String bad : new String[] {null, "", "   ", "no-arroba", "a@b", "a b@example.com", "a@@example.com",
                "x".repeat(250) + "@example.com"}) {
            assertThatThrownBy(() -> service.start(command().email(bad).build()))
                    .as("email %s", bad)
                    .isInstanceOfSatisfying(PackCheckoutException.class,
                            e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EMAIL));
        }
    }

    @Test
    void start_invalidNames_areRefused() {
        for (String bad : new String[] {null, "", " ", "A", "x".repeat(101), "<script>alert(1)</script>", "Ana\u0000"}) {
            assertThatThrownBy(() -> service.start(command().name(bad).build()))
                    .as("name %s", bad)
                    .isInstanceOfSatisfying(PackCheckoutException.class,
                            e -> assertThat(e.error()).isEqualTo(PackCheckoutError.NOMBRE));
        }
    }

    @Test
    void start_unknownEdition_isRefused() {
        assertThatThrownBy(() -> service.start(command().edition("otra").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EDICION));
    }

    @Test
    void start_unknownExtra_isRefused() {
        assertThatThrownBy(() -> service.start(command().extras(List.of("juegos", "regalo")).build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EXTRAS));
    }

    // --- phone and the games add-on ------------------------------------------------------

    @Test
    void start_gamesWithoutAPhone_isRefused() {
        assertThatThrownBy(() -> service.start(command().extras(List.of("juegos")).phone(null).build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.TELEFONO));
        assertThatThrownBy(() -> service.start(command().extras(List.of("juegos")).phone("12").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.TELEFONO));

        verify(orders, never()).save(any());
    }

    @Test
    void start_withoutGames_aPhoneIsOptional_andGarbageIsDroppedInsteadOfBlockingTheSale() throws Exception {
        givenMercadoPagoWorks();

        service.start(command().phone(null).build());
        service.start(command().email("b@example.com").phone("not a phone").build());

        ArgumentCaptor<PackOrder> captor = ArgumentCaptor.forClass(PackOrder.class);
        verify(orders, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(order -> assertThat(order.getPhone()).isNull());
    }

    @Test
    void start_gamesWhenThePhoneAlreadyHasEveryMonth_isRefusedWithAClearError() {
        when(challengePurchases.hasMonthAvailable(any())).thenReturn(false);

        assertThatThrownBy(() -> service.start(command().extras(List.of("juegos")).phone("221 123-4567").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.MESES));

        verify(orders, never()).save(any());
    }

    // --- abuse limits ----------------------------------------------------------------------

    @Test
    void start_tooManyCheckoutsFromOneIp_areLimited() throws Exception {
        properties.getRateLimits().setCheckoutPerIp(2);
        givenMercadoPagoWorks();

        service.start(command().email("a1@example.com").build());
        service.start(command().email("a2@example.com").build());

        assertThatThrownBy(() -> service.start(command().email("a3@example.com").build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.LIMITE));
    }

    @Test
    void start_theIpLimitIsPerIp() throws Exception {
        properties.getRateLimits().setCheckoutPerIp(1);
        givenMercadoPagoWorks();

        service.start(command().clientIp("9.9.9.9").email("a1@example.com").build());

        assertThat(service.start(command().clientIp("8.8.8.8").email("a2@example.com").build())).isEqualTo(INIT_POINT);
    }

    @Test
    void start_tooManyPendingOrdersForOneEmail_areLimited() {
        properties.getRateLimits().setPendingPerEmail(3);
        when(orders.countByEmailAndStatusAndCreatedAtAfter(eq("ana@example.com"), eq(PackOrderStatus.PENDING), any(Instant.class)))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.start(command().build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.LIMITE));

        verify(orders, never()).save(any());
    }

    // --- Mercado Pago down --------------------------------------------------------------------

    @Test
    void start_whenMercadoPagoFails_nothingDeliverableIsLeftBehind() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.save(any(PackOrder.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(mercadoPago.createCheckoutPreference(any()))
                .thenThrow(new MPApiException("down", new MPResponse(500, null, null)));

        assertThatThrownBy(() -> service.start(command().build()))
                .isInstanceOfSatisfying(PackCheckoutException.class, e -> assertThat(e.error()).isEqualTo(PackCheckoutError.PAGO));

        assertThat(lastSavedOrder().getStatus()).isEqualTo(PackOrderStatus.FAILED);
        assertThat(lastSavedOrder().getInitPoint()).isNull();
    }

    // --- invariants ----------------------------------------------------------------------------

    @Test
    void checkout_cannotSendEmail_becauseItHasNoWayToDoSo() {
        // Nothing is emailed before the payment is confirmed (a checkout form must not be usable to
        // send mail to third parties). The service simply does not depend on anything that could.
        List<String> dependencies = java.util.Arrays.stream(PackCheckoutService.class.getDeclaredConstructors()[0].getParameterTypes())
                .map(Class::getSimpleName).toList();

        assertThat(dependencies).doesNotContain("EmailSender", "ApplicationEventPublisher", "PackOrderEmailService");
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void givenMercadoPagoWorks() throws Exception {
        when(mercadoPago.isConfigured()).thenReturn(true);
        when(orders.save(any(PackOrder.class))).thenAnswer(invocation -> withId(invocation.getArgument(0)));
        when(mercadoPago.createCheckoutPreference(any())).thenReturn(new CreatedPreference("PREF-1", INIT_POINT));
    }

    private static PackOrder withId(PackOrder order) {
        if (order.getId() == null) {
            order.setId(42L);
        }
        return order;
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

    /** Valid defaults; each test overrides only what it is about. */
    static final class CommandBuilder {
        private String email = "ana@example.com";
        private String name = "Ana Gómez";
        private String edition = "familias";
        private List<String> extras = List.of();
        private String phone = null;
        private String expectedTotal = null;
        private String utmSource;
        private String utmMedium;
        private String utmCampaign;
        private String utmContent;
        private String clientIp = "1.2.3.4";

        CommandBuilder email(String v) { this.email = v; return this; }
        CommandBuilder name(String v) { this.name = v; return this; }
        CommandBuilder edition(String v) { this.edition = v; return this; }
        CommandBuilder extras(List<String> v) { this.extras = v; return this; }
        CommandBuilder phone(String v) { this.phone = v; return this; }
        CommandBuilder expectedTotal(String v) { this.expectedTotal = v; return this; }
        CommandBuilder utmSource(String v) { this.utmSource = v; return this; }
        CommandBuilder utmMedium(String v) { this.utmMedium = v; return this; }
        CommandBuilder utmCampaign(String v) { this.utmCampaign = v; return this; }
        CommandBuilder clientIp(String v) { this.clientIp = v; return this; }

        CheckoutCommand build() {
            return new CheckoutCommand(email, name, edition, extras, phone, expectedTotal,
                    utmSource, utmMedium, utmCampaign, utmContent, clientIp);
        }
    }
}
