package com.tiam.pack.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.GlobalExceptionHandler;
import com.tiam.pack.dto.RegretReceiptResponse;
import com.tiam.pack.dto.RegretRequest;
import com.tiam.pack.service.PackAccessResendService;
import com.tiam.pack.service.PackRateLimitedException;
import com.tiam.pack.service.PackRegretService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PackAccessAndRegretControllerTest {

    @Mock PackAccessResendService resendService;
    @Mock PackRegretService regretService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PackAccessController(resendService, TestIps.RESOLVER), new PackRegretController(regretService, TestIps.RESOLVER))
                .setControllerAdvice(new PackExceptionHandler(), new GlobalExceptionHandler())
                // same date format as application.yml (spring.jackson.serialization.write-dates-as-timestamps=false)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()))
                .build();
    }

    // --- resend access --------------------------------------------------------------------------------

    @Test
    void resend_alwaysAnswers202WithTheSameMessage_whateverTheEmail() throws Exception {
        String known = mvc.perform(resend("{\"email\":\"buyer@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("Si hay un pedido con ese email, te mandamos el acceso."))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(resend("{\"email\":\"nobody@example.com\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String garbage = mvc.perform(resend("{\"email\":\"definitely not an email\"}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(known).isEqualTo(unknown).isEqualTo(garbage);
    }

    @Test
    void resend_passesTheEmailAndTheClientIpToTheService() throws Exception {
        mvc.perform(resend("{\"email\":\"buyer@example.com\"}")).andExpect(status().isAccepted());

        verify(resendService).request("buyer@example.com", "127.0.0.1");
    }

    @Test
    void resend_stillAnswers202_whenTheServiceDropsTheRequestForQuota() throws Exception {
        // the service swallows over-quota requests silently; the controller has nothing to add
        mvc.perform(resend("{\"email\":\"buyer@example.com\"}")).andExpect(status().isAccepted());
        mvc.perform(resend("{\"email\":\"buyer@example.com\"}")).andExpect(status().isAccepted());
        mvc.perform(resend("{\"email\":\"buyer@example.com\"}")).andExpect(status().isAccepted());
    }

    @Test
    void resend_aMissingOrBlankEmailField_isABadRequest() throws Exception {
        mvc.perform(resend("{}")).andExpect(status().isBadRequest());
        mvc.perform(resend("{\"email\":\"   \"}")).andExpect(status().isBadRequest());
        mvc.perform(resend("not json")).andExpect(status().isBadRequest());

        verifyNoInteractions(resendService);
    }

    // --- regret form -------------------------------------------------------------------------------------

    @Test
    void regret_returns201WithTheReceipt() throws Exception {
        when(regretService.submit(any(), eq("127.0.0.1")))
                .thenReturn(new RegretReceiptResponse("ARR-000012", Instant.parse("2026-10-14T15:30:00Z")));

        mvc.perform(regret("{\"fullName\":\"Ana Gómez\",\"email\":\"ana@example.com\",\"orderRef\":\"42\",\"reason\":\"No lo necesito\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reference").value("ARR-000012"))
                .andExpect(jsonPath("$.data.receivedAt").value("2026-10-14T15:30:00Z"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        ArgumentCaptor<RegretRequest> captor = ArgumentCaptor.forClass(RegretRequest.class);
        verify(regretService).submit(captor.capture(), eq("127.0.0.1"));
        assertThat(captor.getValue()).isEqualTo(new RegretRequest("Ana Gómez", "ana@example.com", "42", "No lo necesito"));
    }

    @Test
    void regret_orderRefAndReasonAreOptional() throws Exception {
        when(regretService.submit(any(), any())).thenReturn(new RegretReceiptResponse("ARR-000001", Instant.now()));

        mvc.perform(regret("{\"fullName\":\"Ana Gómez\",\"email\":\"ana@example.com\"}")).andExpect(status().isCreated());
    }

    @Test
    void regret_missingRequiredFieldsOrTooLongText_isABadRequest() throws Exception {
        mvc.perform(regret("{\"email\":\"ana@example.com\"}")).andExpect(status().isBadRequest());
        mvc.perform(regret("{\"fullName\":\"Ana Gómez\"}")).andExpect(status().isBadRequest());
        mvc.perform(regret("{\"fullName\":\"A\",\"email\":\"ana@example.com\"}")).andExpect(status().isBadRequest());
        mvc.perform(regret("{\"fullName\":\"Ana\",\"email\":\"ana@example.com\",\"reason\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(regretService);
    }

    @Test
    void regret_anEmailThatIsNotValid_isABadRequestWithAClearMessage() throws Exception {
        when(regretService.submit(any(), any())).thenThrow(new BadRequestException("El email no es válido"));

        mvc.perform(regret("{\"fullName\":\"Ana Gómez\",\"email\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El email no es válido"));
    }

    @Test
    void regret_overQuota_is429() throws Exception {
        doThrow(new PackRateLimitedException()).when(regretService).submit(any(), any());

        mvc.perform(regret("{\"fullName\":\"Ana Gómez\",\"email\":\"ana@example.com\"}"))
                .andExpect(status().isTooManyRequests());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder resend(String json) {
        return post("/packs/access/resend").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder regret(String json) {
        return post("/packs/regret").contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
