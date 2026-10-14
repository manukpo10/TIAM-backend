package com.tiam.pack.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.GlobalExceptionHandler;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.service.PackPaymentConfirmationService;
import com.tiam.pack.service.PackRateLimiter;
import com.tiam.pack.service.PackUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PackOrderControllerTest {

    final String token = PackTokens.newToken();

    @Mock PackPaymentConfirmationService confirmationService;

    PackProperties properties;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        PackOrderController controller = new PackOrderController(confirmationService, new PackRateLimiter(), properties);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PackExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void confirm_returnsTheStatus_inTheStandardEnvelope_withoutCachingOrReferrers() throws Exception {
        when(confirmationService.confirm(token, "987654")).thenReturn(PackOrderStatus.PAID);

        mvc.perform(confirm(token, "{\"paymentId\":\"987654\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void confirm_neverExposesInternalStates() throws Exception {
        when(confirmationService.confirm(token, "987654")).thenReturn(PackOrderStatus.REVIEW);

        mvc.perform(confirm(token, "{\"paymentId\":\"987654\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void confirm_badBodies_areABadRequest_andNeverReachTheService() throws Exception {
        for (String body : new String[] {"{}", "{\"paymentId\":\"\"}", "{\"paymentId\":\"abc\"}",
                "{\"paymentId\":\"1; drop table\"}", "{\"paymentId\":\"1234567890123456789012\"}"}) {
            mvc.perform(confirm(token, body)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(confirmationService);
    }

    @Test
    void confirm_aBodyThatIsNotJson_isABadRequestNotAServerError() throws Exception {
        mvc.perform(confirm(token, "this is not json")).andExpect(status().isBadRequest());

        verifyNoInteractions(confirmationService);
    }

    @Test
    void confirm_unknownToken_isNotFound_andTheResponseDoesNotEchoTheToken() throws Exception {
        when(confirmationService.confirm(any(), any())).thenThrow(new ResourceNotFoundException("Order not found"));

        String body = mvc.perform(confirm(token, "{\"paymentId\":\"987654\"}"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(token);
    }

    @Test
    void confirm_aPaymentOfAnotherOrder_isABadRequest() throws Exception {
        when(confirmationService.confirm(any(), any())).thenThrow(new BadRequestException("The payment does not belong to this order"));

        mvc.perform(confirm(token, "{\"paymentId\":\"987654\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void confirm_mercadoPagoUnavailable_is503WithRetryAfter() throws Exception {
        when(confirmationService.confirm(any(), any())).thenThrow(new PackUnavailableException("down"));

        mvc.perform(confirm(token, "{\"paymentId\":\"987654\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void confirm_tooManyCallsFromOneIp_are429() throws Exception {
        properties.getRateLimits().setTokenCallsPerIp(2);
        when(confirmationService.confirm(any(), any())).thenReturn(PackOrderStatus.PENDING);

        mvc.perform(confirm(token, "{\"paymentId\":\"1\"}")).andExpect(status().isOk());
        mvc.perform(confirm(token, "{\"paymentId\":\"1\"}")).andExpect(status().isOk());
        mvc.perform(confirm(token, "{\"paymentId\":\"1\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));

        verify(confirmationService, org.mockito.Mockito.times(2)).confirm(any(), any());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm(String token, String json) {
        return post("/packs/orders/" + token + "/confirm").contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
