package com.tiam.pack.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The same {@code POST /packs/checkout}, with {@code edicion=cuadernos}: the landing it returns to is {@code /cuadernos}. */
@ExtendWith(MockitoExtension.class)
class CuadernosCheckoutControllerTest {

    static final String MP = "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=PREF-C1";
    static final String LANDING = "https://tiamdigital.com";

    @Mock PackCheckoutService checkoutService;

    PackRedirects redirects;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        PackProperties properties = new PackProperties();
        properties.setLandingBaseUrl(LANDING);
        redirects = new PackRedirects(properties);
        PackCheckoutController controller = new PackCheckoutController(
                checkoutService, new PackOriginPolicy("https://tiamdigital.com,https://www.tiamdigital.com"),
                redirects, TestIps.RESOLVER);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // --- redirects ------------------------------------------------------------------------------------

    @Test
    void landing_ofTheCuadernos_isTheTopLevelCuadernosPage() {
        assertThat(redirects.landing("cuadernos", PackCheckoutError.CERRADO)).isEqualTo(LANDING + "/cuadernos?error=cerrado#comprar");
        assertThat(redirects.landing(" Cuadernos ", PackCheckoutError.PRECIO)).isEqualTo(LANDING + "/cuadernos?error=precio#comprar");
        assertThat(redirects.landing("cuadernos", null)).isEqualTo(LANDING + "/cuadernos#comprar");
    }

    @Test
    void landing_ofThePack_isUnchanged() {
        assertThat(redirects.landing("familias", PackCheckoutError.CERRADO)).isEqualTo(LANDING + "/pack/familias?error=cerrado#comprar");
        assertThat(redirects.landing("profesionales", null)).isEqualTo(LANDING + "/pack/profesionales#comprar");
        assertThat(redirects.landing("otra", PackCheckoutError.EDICION)).isEqualTo(LANDING + "/pack/familias?error=edicion#comprar");
        assertThat(redirects.landing(null, null)).isEqualTo(LANDING + "/pack/familias#comprar");
    }

    @Test
    void landing_stripsTrailingSlashesOfTheBase() {
        PackProperties properties = new PackProperties();
        properties.setLandingBaseUrl("https://tiamdigital.com//");

        assertThat(new PackRedirects(properties).landing("cuadernos", PackCheckoutError.CERRADO))
                .isEqualTo("https://tiamdigital.com/cuadernos?error=cerrado#comprar");
    }

    // --- the form ----------------------------------------------------------------------------------------

    @Test
    void validForm_redirectsWith303ToTheMercadoPagoCheckout_andPassesTheEditionAlong() throws Exception {
        when(checkoutService.start(any())).thenReturn(MP);

        mvc.perform(form().param("extras", "juegos").param("telefono", "221 123-4567").param("expected_total", "30000"))
                .andExpect(status().isSeeOther()).andExpect(header().string("Location", MP))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        ArgumentCaptor<CheckoutCommand> captor = ArgumentCaptor.forClass(CheckoutCommand.class);
        verify(checkoutService).start(captor.capture());
        assertThat(captor.getValue().edition()).isEqualTo("cuadernos");
        assertThat(captor.getValue().extras()).containsExactly("juegos");
        assertThat(captor.getValue().expectedTotal()).isEqualTo("30000");
    }

    @Test
    void salesOff_redirectsBackToTheCuadernosLandingWithErrorCerrado() throws Exception {
        when(checkoutService.start(any())).thenThrow(new PackCheckoutException(PackCheckoutError.CERRADO));

        mvc.perform(form()).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/cuadernos?error=cerrado#comprar"));
    }

    @Test
    void theExtraBooklet_comesBackAsTheExtrasError_onTheCuadernosLanding() throws Exception {
        when(checkoutService.start(any())).thenThrow(new PackCheckoutException(PackCheckoutError.EXTRAS));

        mvc.perform(form().param("extras", "cuadernillo")).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/cuadernos?error=extras#comprar"));
    }

    @Test
    void everyErrorCode_travelsInTheQueryString_toTheCuadernosLanding() throws Exception {
        for (PackCheckoutError error : PackCheckoutError.values()) {
            org.mockito.Mockito.reset(checkoutService);
            when(checkoutService.start(any())).thenThrow(new PackCheckoutException(error));

            mvc.perform(form()).andExpect(status().isSeeOther())
                    .andExpect(header().string("Location", LANDING + "/cuadernos?error=" + error.code() + "#comprar"));
        }
    }

    @Test
    void englishAlias_edition_isAcceptedToo() throws Exception {
        when(checkoutService.start(any())).thenThrow(new PackCheckoutException(PackCheckoutError.CERRADO));

        mvc.perform(post("/packs/checkout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "ana@example.com").param("name", "Ana Gómez").param("edition", "cuadernos"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/cuadernos?error=cerrado#comprar"));
    }

    @Test
    void unexpectedFailure_stillRedirectsToTheCuadernosLanding() throws Exception {
        when(checkoutService.start(any())).thenThrow(new IllegalStateException("db down"));

        mvc.perform(form()).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/cuadernos?error=interno#comprar"));
    }

    @Test
    void paymentUrlThatIsNotMercadoPago_isNeverFollowed() throws Exception {
        when(checkoutService.start(any())).thenReturn("https://evil.example.com/steal");

        mvc.perform(form()).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/cuadernos?error=pago#comprar"));
    }

    @Test
    void filledHoneypot_createsNothing_andReturnsToTheCuadernosLanding() throws Exception {
        mvc.perform(form().param("hp_contacto", "http://spam.example.com")).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/cuadernos#comprar"));

        verifyNoInteractions(checkoutService);
    }

    @Test
    void postFromADisallowedOrigin_isRefusedAndSentBackToTheCuadernosLanding() throws Exception {
        mvc.perform(form().header("Origin", "https://evil.example.com")).andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/cuadernos?error=origen#comprar"));

        verifyNoInteractions(checkoutService);
    }

    private static MockHttpServletRequestBuilder form() {
        return post("/packs/checkout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("email", "ana@example.com").param("nombre", "Ana Gómez").param("edicion", "cuadernos");
    }
}
