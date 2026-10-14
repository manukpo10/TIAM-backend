package com.tiam.pack.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.tiam.pack.dto.DownloadLinkResponse;
import com.tiam.pack.dto.PackOrderResponse;
import com.tiam.pack.service.PackDownloadLimitException;
import com.tiam.pack.service.PackDownloadService;
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
    @Mock PackDownloadService downloadService;

    PackProperties properties;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        PackOrderController controller = new PackOrderController(confirmationService, downloadService, new PackRateLimiter(), properties, TestIps.RESOLVER);
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
    void confirm_aPaymentHeldForReview_isShownAsReview_notDisguisedAsPending() throws Exception {
        when(confirmationService.confirm(token, "987654")).thenReturn(PackOrderStatus.REVIEW);

        mvc.perform(confirm(token, "{\"paymentId\":\"987654\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVIEW"));
    }

    @Test
    void confirm_aRefundedPayment_neverLeaksTheInternalRevokedState() throws Exception {
        when(confirmationService.confirm(token, "987654")).thenReturn(PackOrderStatus.REVOKED);

        mvc.perform(confirm(token, "{\"paymentId\":\"987654\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
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
    void anUnexpectedFailure_isA500_whoseBodyAndLogsNeverCarryTheToken() throws Exception {
        when(downloadService.view(any())).thenThrow(new IllegalStateException("database exploded"));

        try (com.tiam.testsupport.LogCapture logs = new com.tiam.testsupport.LogCapture(PackExceptionHandler.class)) {
            String body = mvc.perform(get("/packs/orders/" + token))
                    .andExpect(status().isInternalServerError())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(token).contains("/packs/orders/{token}");
            assertThat(logs.allMessages()).isNotEmpty().noneMatch(message -> message.contains(token));
        }
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

    // --- GET /packs/orders/{token} ------------------------------------------------------------------

    @Test
    void get_returnsThePaidOrderInTheStandardEnvelope_withoutCachingOrReferrers() throws Exception {
        PackOrderResponse view = PackOrderResponse.paid("Ana", "familias",
                java.util.List.of(new PackOrderResponse.Item("PACK", "Pack imprimible - Edición Familias",
                        java.util.List.of(new PackOrderResponse.FileInfo("pack-completo-a4", "Pack completo (1 PDF)", "A4", 19)))),
                null, java.time.Instant.parse("2027-01-12T15:00:00Z"), new java.math.BigDecimal("14990"));
        when(downloadService.view(token)).thenReturn(view);

        mvc.perform(get("/packs/orders/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.firstName").value("Ana"))
                .andExpect(jsonPath("$.data.edition").value("familias"))
                .andExpect(jsonPath("$.data.items[0].code").value("PACK"))
                .andExpect(jsonPath("$.data.items[0].files[0].key").value("pack-completo-a4"))
                .andExpect(jsonPath("$.data.items[0].files[0].paper").value("A4"))
                .andExpect(jsonPath("$.data.items[0].files[0].sizeMb").value(19))
                .andExpect(jsonPath("$.data.total").value(14990))
                .andExpect(jsonPath("$.data.games").doesNotExist())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void get_pendingOrder_returnsOnlyItsStatus() throws Exception {
        when(downloadService.view(token)).thenReturn(PackOrderResponse.withoutFiles("PENDING", "familias"));

        mvc.perform(get("/packs/orders/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.edition").value("familias"))
                .andExpect(jsonPath("$.data.total").doesNotExist())
                .andExpect(jsonPath("$.data.firstName").doesNotExist());
    }

    @Test
    void get_sharedAddressesAreNotStarved_eachTokenHasItsOwnBucket() throws Exception {
        properties.getRateLimits().setTokenCallsPerIp(1);
        when(downloadService.view(any())).thenReturn(PackOrderResponse.withoutFiles("PENDING", "familias"));

        for (int i = 0; i < 20; i++) { // twenty buyers behind one carrier address, one page each
            mvc.perform(get("/packs/orders/" + PackTokens.newToken())).andExpect(status().isOk());
        }
    }

    @Test
    void get_aSingleAddressStillHasAnOverallCeiling() throws Exception {
        properties.getRateLimits().setTokenCallsPerIpTotal(3);
        when(downloadService.view(any())).thenReturn(PackOrderResponse.withoutFiles("PENDING", "familias"));

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/packs/orders/" + PackTokens.newToken())).andExpect(status().isOk());
        }
        mvc.perform(get("/packs/orders/" + PackTokens.newToken())).andExpect(status().isTooManyRequests());
    }

    @Test
    void get_unknownToken_is404_andTheBodyNeverContainsTheToken() throws Exception {
        when(downloadService.view(any())).thenThrow(new ResourceNotFoundException("Not found"));

        String body = mvc.perform(get("/packs/orders/" + token))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(token).contains("/packs/orders/{token}");
    }

    @Test
    void get_isRateLimitedPerIp() throws Exception {
        properties.getRateLimits().setTokenCallsPerIp(1);
        when(downloadService.view(any())).thenReturn(PackOrderResponse.withoutFiles("PENDING", "familias"));

        mvc.perform(get("/packs/orders/" + token)).andExpect(status().isOk());
        mvc.perform(get("/packs/orders/" + token)).andExpect(status().isTooManyRequests());
    }

    // --- POST /packs/orders/{token}/files/{key}/link --------------------------------------------------

    @Test
    void issueLink_returnsTheSignedUrlAndItsLifetime() throws Exception {
        when(downloadService.issueLink(token, "pack-completo-a4"))
                .thenReturn(new DownloadLinkResponse("https://files.example.com/x?sig=1", 300));

        mvc.perform(post("/packs/orders/" + token + "/files/pack-completo-a4/link"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://files.example.com/x?sig=1"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void issueLink_isPostOnly_aGetIsNotAllowed() throws Exception {
        // (the app-wide handler turns a wrong-method error into a generic failure; what matters is that
        // no link is issued and nothing succeeds)
        mvc.perform(get("/packs/orders/" + token + "/files/pack-completo-a4/link"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));

        verifyNoInteractions(downloadService);
    }

    @Test
    void issueLink_unknownFileOrOrder_is404() throws Exception {
        when(downloadService.issueLink(any(), any())).thenThrow(new ResourceNotFoundException("Not found"));

        mvc.perform(post("/packs/orders/" + token + "/files/extra-a4/link")).andExpect(status().isNotFound());
    }

    @Test
    void issueLink_dailyCap_is429WithAClearMessage() throws Exception {
        when(downloadService.issueLink(any(), any()))
                .thenThrow(new PackDownloadLimitException(PackDownloadLimitException.Cap.DAILY));

        mvc.perform(post("/packs/orders/" + token + "/files/pack-completo-a4/link"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("hoy")))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void issueLink_totalCap_is429WithADifferentMessage() throws Exception {
        when(downloadService.issueLink(any(), any()))
                .thenThrow(new PackDownloadLimitException(PackDownloadLimitException.Cap.TOTAL));

        mvc.perform(post("/packs/orders/" + token + "/files/pack-completo-a4/link"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("total")));
    }

    @Test
    void issueLink_storageDown_is503() throws Exception {
        when(downloadService.issueLink(any(), any())).thenThrow(new PackUnavailableException("storage"));

        mvc.perform(post("/packs/orders/" + token + "/files/pack-completo-a4/link"))
                .andExpect(status().isServiceUnavailable());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm(String token, String json) {
        return post("/packs/orders/" + token + "/confirm").contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
