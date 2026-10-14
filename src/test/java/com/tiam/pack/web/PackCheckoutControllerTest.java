package com.tiam.pack.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tiam.pack.config.PackProperties;
import com.tiam.pack.service.CheckoutCommand;
import com.tiam.pack.service.PackCheckoutError;
import com.tiam.pack.service.PackCheckoutException;
import com.tiam.pack.service.PackCheckoutService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PackCheckoutControllerTest {

    static final String MP = "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=PREF-1";
    static final String LANDING = "https://tiamdigital.com";

    @Mock PackCheckoutService checkoutService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        PackProperties properties = new PackProperties();
        properties.setLandingBaseUrl(LANDING);
        PackCheckoutController controller = new PackCheckoutController(
                checkoutService, new PackOriginPolicy("https://tiamdigital.com,https://www.tiamdigital.com"),
                new PackRedirects(properties), TestIps.RESOLVER);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // --- success -------------------------------------------------------------------------------

    @Test
    void validForm_redirectsWith303ToTheMercadoPagoCheckout() throws Exception {
        when(checkoutService.start(any())).thenReturn(MP);

        mvc.perform(form()).andExpect(status().isSeeOther()).andExpect(header().string("Location", MP))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void validForm_passesEveryFieldToTheService_includingTheRepeatedExtras() throws Exception {
        when(checkoutService.start(any())).thenReturn(MP);

        mvc.perform(form().param("extras", "juegos", "cuadernillo").param("telefono", "221 123-4567")
                        .param("expected_total", "29980").param("utm_source", "facebook").param("utm_medium", "cpc")
                        .param("utm_campaign", "otoño").param("utm_content", "video-1"))
                .andExpect(status().isSeeOther());

        ArgumentCaptor<CheckoutCommand> captor = ArgumentCaptor.forClass(CheckoutCommand.class);
        verify(checkoutService).start(captor.capture());
        CheckoutCommand command = captor.getValue();
        assertThat(command.email()).isEqualTo("ana@example.com");
        assertThat(command.name()).isEqualTo("Ana Gómez");
        assertThat(command.edition()).isEqualTo("familias");
        assertThat(command.extras()).containsExactly("juegos", "cuadernillo");
        assertThat(command.phone()).isEqualTo("221 123-4567");
        assertThat(command.expectedTotal()).isEqualTo("29980");
        assertThat(command.utmSource()).isEqualTo("facebook");
        assertThat(command.utmMedium()).isEqualTo("cpc");
        assertThat(command.utmCampaign()).isEqualTo("otoño");
        assertThat(command.utmContent()).isEqualTo("video-1");
        assertThat(command.clientIp()).isEqualTo("127.0.0.1");
    }

    @Test
    void englishFieldNames_areAcceptedAsAliases() throws Exception {
        when(checkoutService.start(any())).thenReturn(MP);

        mvc.perform(post("/packs/checkout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "ana@example.com").param("name", "Ana Gómez")
                        .param("edition", "profesionales").param("phone", "221 123-4567"))
                .andExpect(status().isSeeOther());

        ArgumentCaptor<CheckoutCommand> captor = ArgumentCaptor.forClass(CheckoutCommand.class);
        verify(checkoutService).start(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Ana Gómez");
        assertThat(captor.getValue().edition()).isEqualTo("profesionales");
        assertThat(captor.getValue().phone()).isEqualTo("221 123-4567");
    }

    // --- errors always go back to the landing page ----------------------------------------------

    @Test
    void checkoutError_redirectsBackToTheEditionLandingWithTheErrorCode() throws Exception {
        when(checkoutService.start(any())).thenThrow(new PackCheckoutException(PackCheckoutError.PRECIO));

        mvc.perform(formWithoutEdition().param("edicion", "profesionales"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/pack/profesionales?error=precio#comprar"));
    }

    @Test
    void everyErrorCode_travelsInTheQueryString() throws Exception {
        for (PackCheckoutError error : PackCheckoutError.values()) {
            org.mockito.Mockito.reset(checkoutService);
            when(checkoutService.start(any())).thenThrow(new PackCheckoutException(error));

            mvc.perform(form()).andExpect(status().isSeeOther())
                    .andExpect(header().string("Location", LANDING + "/pack/familias?error=" + error.code() + "#comprar"));
        }
    }

    @Test
    void unexpectedFailure_stillRedirectsInsteadOfShowingAnErrorPage() throws Exception {
        when(checkoutService.start(any())).thenThrow(new IllegalStateException("db down"));

        mvc.perform(form()).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/pack/familias?error=interno#comprar"));
    }

    @Test
    void paymentUrlThatIsNotMercadoPago_isNeverFollowed() throws Exception {
        when(checkoutService.start(any())).thenReturn("https://evil.example.com/steal");

        mvc.perform(form()).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/pack/familias?error=pago#comprar"));
    }

    // --- bots and cross-site posts ---------------------------------------------------------------

    @Test
    void filledHoneypot_createsNothing_andJustReturnsToTheLanding() throws Exception {
        mvc.perform(form().param("web", "http://spam.example.com"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/pack/familias#comprar"));

        verifyNoInteractions(checkoutService);
    }

    @Test
    void emptyHoneypot_isAnOrdinaryBuyer() throws Exception {
        when(checkoutService.start(any())).thenReturn(MP);

        mvc.perform(form().param("web", "")).andExpect(status().isSeeOther()).andExpect(header().string("Location", MP));
    }

    @Test
    void postFromADisallowedOrigin_isRefusedBeforeReachingTheService() throws Exception {
        mvc.perform(form().header("Origin", "https://evil.example.com"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/pack/familias?error=origen#comprar"));

        verify(checkoutService, never()).start(any());
    }

    @Test
    void postFromTheLiteralNullOrigin_isRefusedToo() throws Exception {
        mvc.perform(form().header("Origin", "null")).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/pack/familias?error=origen#comprar"));
    }

    @Test
    void postFromAnAllowedOrigin_proceeds() throws Exception {
        when(checkoutService.start(any())).thenReturn(MP);

        mvc.perform(form().header("Origin", "https://www.tiamdigital.com"))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", MP));
    }

    @Test
    void postWithoutAnOriginHeader_proceeds() throws Exception {
        when(checkoutService.start(any())).thenReturn(MP);

        mvc.perform(form()).andExpect(status().isSeeOther()).andExpect(header().string("Location", MP));
    }

    // --- no open redirect --------------------------------------------------------------------------

    @Test
    void noRequestParameterCanChooseTheRedirectDestination() throws Exception {
        when(checkoutService.start(any())).thenThrow(new PackCheckoutException(PackCheckoutError.EMAIL));

        for (String param : new String[] {"redirect", "redirect_to", "next", "return", "return_url", "url", "callback"}) {
            mvc.perform(form().param(param, "https://evil.example.com/phish"))
                    .andExpect(status().isSeeOther())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(LANDING + "/pack/")))
                    .andExpect(header().string("Location", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("evil"))));
        }
    }

    @Test
    void editionIsNeverEchoedIntoTheRedirect_soAHostileValueFallsBackToTheDefaultLanding() throws Exception {
        when(checkoutService.start(any())).thenThrow(new PackCheckoutException(PackCheckoutError.EDICION));

        mvc.perform(formWithoutEdition().param("edicion", "//evil.example.com/x?"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/pack/familias?error=edicion#comprar"));
    }

    // --- content type ---------------------------------------------------------------------------------

    @Test
    void theBodyMustBeAFormPost_jsonIsNotAccepted() throws Exception {
        mvc.perform(post("/packs/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@example.com\"}"))
                .andExpect(status().isUnsupportedMediaType());

        verifyNoInteractions(checkoutService);
    }

    @Test
    void mercadoPagoUrlValidation_acceptsOnlyHttpsMercadoPagoHosts() {
        assertThat(PackRedirects.isMercadoPagoUrl(MP)).isTrue();
        assertThat(PackRedirects.isMercadoPagoUrl("https://sandbox.mercadopago.com.ar/checkout/v1/redirect?pref_id=1")).isTrue();
        assertThat(PackRedirects.isMercadoPagoUrl("https://www.mercadopago.com/checkout/v1/redirect")).isTrue();
        assertThat(PackRedirects.isMercadoPagoUrl("http://www.mercadopago.com.ar/x")).isFalse();
        assertThat(PackRedirects.isMercadoPagoUrl("https://mercadopago.com.ar.evil.com/x")).isFalse();
        assertThat(PackRedirects.isMercadoPagoUrl("https://evilmercadopago.com/x")).isFalse();
        assertThat(PackRedirects.isMercadoPagoUrl("https://user@www.mercadopago.com.ar/x")).isFalse();
        assertThat(PackRedirects.isMercadoPagoUrl("javascript:alert(1)")).isFalse();
        assertThat(PackRedirects.isMercadoPagoUrl(null)).isFalse();
        assertThat(PackRedirects.isMercadoPagoUrl("")).isFalse();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder form() {
        return formWithoutEdition().param("edicion", "familias");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder formWithoutEdition() {
        return post("/packs/checkout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", "ana@example.com").param("nombre", "Ana Gómez");
    }
}
