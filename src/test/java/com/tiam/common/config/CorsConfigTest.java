package com.tiam.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tiam.common.web.ClientIpProperties;
import com.tiam.common.web.ClientIpResolver;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.service.PackCheckoutService;
import com.tiam.pack.web.PackCheckoutController;
import com.tiam.pack.web.PackOriginPolicy;
import com.tiam.pack.web.PackRedirects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * The checkout is a plain HTML form post (a full-page navigation). Spring's CORS filter used to answer a post from
 * an origin that is not in CORS_ALLOWED_ORIGINS (the apex vs www, a Vercel preview, a stripped Origin) with a bare
 * "403 Invalid CORS request" page, so the buyer never saw the friendly "?error=origen" redirect. The form post is
 * therefore left to PackOriginPolicy, which redirects; every fetch endpoint keeps the strict CORS check.
 */
class CorsConfigTest {

    static final String LANDING = "https://tiamdigital.com";

    CorsConfigurationSource source;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOriginsRaw", LANDING + ",https://www.tiamdigital.com");
        source = config.corsConfigurationSource();

        PackProperties properties = new PackProperties();
        properties.setLandingBaseUrl(LANDING);
        PackCheckoutController checkout = new PackCheckoutController(mock(PackCheckoutService.class),
                new PackOriginPolicy(LANDING + ",https://www.tiamdigital.com"), new PackRedirects(properties),
                new ClientIpResolver(new ClientIpProperties()));
        mvc = MockMvcBuilders.standaloneSetup(checkout, new ProbeController())
                .addFilters(new CorsFilter(source))
                .build();
    }

    @RestController
    static class ProbeController {
        @GetMapping("/packs/orders/probe")
        String probe() {
            return "ok";
        }
    }

    @Test
    void checkoutFormPost_hasNoCorsConfiguration_soTheControllerDecidesWithAFriendlyRedirect() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/packs/checkout");

        assertThat(source.getCorsConfiguration(request)).isNull();
    }

    @Test
    void everyOtherEndpoint_keepsTheStrictOriginList() {
        for (String path : new String[] {"/packs/orders/abc", "/packs/regret", "/packs/access/resend", "/auth/login"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);

            assertThat(source.getCorsConfiguration(request)).as(path).isNotNull();
            assertThat(source.getCorsConfiguration(request).getAllowedOrigins())
                    .containsExactly(LANDING, "https://www.tiamdigital.com");
        }
    }

    @Test
    void aFormPostFromAnUnlistedOrigin_getsTheFriendlyRedirect_notABare403() throws Exception {
        mvc.perform(post("/packs/checkout").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("Origin", "https://tiam-preview-abc.vercel.app")
                        .param("email", "ana@example.com").param("edicion", "familias"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", LANDING + "/pack/familias?error=origen#comprar"));
    }

    @Test
    void aFetchFromAnUnlistedOrigin_isStillRefusedByCors() throws Exception {
        mvc.perform(get("/packs/orders/probe").header("Origin", "https://evil.example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aFetchFromAListedOrigin_isAllowed() throws Exception {
        mvc.perform(get("/packs/orders/probe").header("Origin", LANDING))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", LANDING));
    }
}
