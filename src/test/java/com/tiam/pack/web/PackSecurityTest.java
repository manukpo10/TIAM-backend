package com.tiam.pack.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tiam.common.config.CorsConfig;
import com.tiam.pack.storage.PrivateFileStore;
import com.tiam.security.CustomUserDetailsService;
import com.tiam.security.JwtAuthFilter;
import com.tiam.security.JwtService;
import com.tiam.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Runs the REAL security filter chain: the pack endpoints must be public (buyers have no account), and
 * adding them must not have opened anything else.
 */
@WebMvcTest(controllers = PackLocalFileController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, CorsConfig.class})
class PackSecurityTest {

    @Autowired MockMvc mvc;

    @MockBean JwtService jwtService;
    @MockBean CustomUserDetailsService customUserDetailsService;
    @MockBean PrivateFileStore privateFileStore;

    @Test
    void packsPaths_arePublic_noLoginRequired() throws Exception {
        // Whatever the handler answers (or the lack of one), it must not be the security chain saying no.
        mvc.perform(get("/packs/anything-at-all")).andExpect(passedSecurity());
        mvc.perform(post("/packs/orders/some-token/confirm")).andExpect(passedSecurity());
        mvc.perform(get("/packs/dev-files?key=k&exp=1&name=n&sig=s")).andExpect(status().isNotFound());
    }

    private static ResultMatcher passedSecurity() {
        return result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
    }

    @Test
    void everythingElse_stillRequiresAuthentication() throws Exception {
        mvc.perform(get("/exercises")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/anything")).andExpect(status().isForbidden());
        mvc.perform(post("/packs-but-not-really")).andExpect(status().isForbidden());
        mvc.perform(get("/pack")).andExpect(status().isForbidden());
    }
}
