package com.tiam.pack.web;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.exception.GlobalExceptionHandler;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.domain.PackTokens;
import com.tiam.pack.dto.DownloadLinkResponse;
import com.tiam.pack.dto.RegretReceiptResponse;
import com.tiam.pack.repository.PackDownloadEventRepository;
import com.tiam.pack.repository.PackOrderRepository;
import com.tiam.pack.service.PackAccessResendService;
import com.tiam.pack.service.PackDownloadService;
import com.tiam.pack.service.PackPaymentConfirmationService;
import com.tiam.pack.service.PackRateLimiter;
import com.tiam.pack.service.PackRegretService;
import com.tiam.pack.storage.PrivateFileStore;
import com.tiam.testsupport.MutableClock;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.skyscreamer.jsonassert.JSONAssert;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The shared contract test. The JSON files under {@code src/test/resources/contract} are the exact bodies the
 * backend returns; the frontend keeps verbatim copies and type-checks them against its TypeScript types at build
 * time (see {@code TIAM-frontend/src/pages/pack/contract}). Change a body here and this test fails until the
 * fixture is updated, and the frontend build then fails until its types are updated too: neither side can drift
 * silently. The responses are produced by the REAL services and DTOs, not hand-written mocks.
 */
@ExtendWith(MockitoExtension.class)
class PackContractTest {

    static final Instant NOW = Instant.parse("2026-10-14T15:00:00Z");

    final String token = PackTokens.newToken();

    @Mock PackOrderRepository orders;
    @Mock PackDownloadEventRepository downloads;
    @Mock PrivateFileStore store;
    @Mock ChallengePurchaseService challengePurchases;
    @Mock PackPaymentConfirmationService confirmationService;
    @Mock PackAccessResendService resendService;
    @Mock PackRegretService regretService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        PackProperties properties = new PackProperties();
        PackDownloadService downloadService = new PackDownloadService(
                orders, downloads, store, challengePurchases, properties, new MutableClock(NOW));
        mvc = MockMvcBuilders.standaloneSetup(
                        new PackOrderController(confirmationService, downloadService, new PackRateLimiter(), properties, TestIps.RESOLVER),
                        new PackAccessController(resendService, TestIps.RESOLVER),
                        new PackRegretController(regretService, TestIps.RESOLVER))
                .setControllerAdvice(new PackExceptionHandler(), new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(productionLikeMapper()))
                .build();
    }

    @Test
    void paidOrder_matchesTheFixture() throws Exception {
        PackOrder order = order(PackOrderStatus.PAID, PackEdition.FAMILIAS);
        order.setWithExtra(true);
        order.setWithGames(true);
        order.setChallengePurchaseId(77L);
        order.setTotalAmount(new BigDecimal("29980"));
        order.setAccessExpiresAt(Instant.parse("2027-01-12T15:00:00Z"));
        when(challengePurchases.findAccessLinkIfActive(77L)).thenReturn(Optional.of("https://tiamdigital.com/desafio/abc123"));

        assertBody("order-paid.json", get("/packs/orders/" + token));
    }

    @Test
    void pendingOrder_matchesTheFixture() throws Exception {
        order(PackOrderStatus.PENDING, PackEdition.FAMILIAS);

        assertBody("order-pending.json", get("/packs/orders/" + token));
    }

    @Test
    void orderHeldForReview_matchesTheFixture() throws Exception {
        order(PackOrderStatus.REVIEW, PackEdition.PROFESIONALES);

        assertBody("order-review.json", get("/packs/orders/" + token));
    }

    @Test
    void failedOrder_matchesTheFixture() throws Exception {
        order(PackOrderStatus.FAILED, PackEdition.FAMILIAS);

        assertBody("order-failed.json", get("/packs/orders/" + token));
    }

    @Test
    void confirm_paid_matchesTheFixture() throws Exception {
        when(confirmationService.confirm(token, "987654")).thenReturn(PackOrderStatus.PAID);

        assertBody("confirm-paid.json", confirm());
    }

    @Test
    void confirm_review_matchesTheFixture() throws Exception {
        when(confirmationService.confirm(token, "987654")).thenReturn(PackOrderStatus.REVIEW);

        assertBody("confirm-review.json", confirm());
    }

    @Test
    void downloadLink_matchesTheFixture() throws Exception {
        PackOrder order = order(PackOrderStatus.PAID, PackEdition.FAMILIAS);
        order.setAccessExpiresAt(Instant.parse("2027-01-12T15:00:00Z"));
        lenient().when(orders.findWithLockById(42L)).thenReturn(Optional.of(order));
        when(store.signedDownloadUrl(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("https://example.r2.cloudflarestorage.com/packs/v1/familias/pack-completo-a4.pdf?X-Amz-Expires=300");

        assertBody("download-link.json", post("/packs/orders/" + token + "/files/pack-completo-a4/link"));
    }

    @Test
    void regretReceipt_matchesTheFixture() throws Exception {
        when(regretService.submit(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new RegretReceiptResponse("ARR-000012", Instant.parse("2026-10-14T15:30:00Z")));

        assertBody("regret-receipt.json", post("/packs/regret").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Ana Gómez\",\"email\":\"ana@example.com\",\"orderRef\":\"42\",\"reason\":\"No lo necesito\"}"));
    }

    @Test
    void resendAccepted_matchesTheFixture() throws Exception {
        assertBody("resend-accepted.json", post("/packs/access/resend").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@example.com\"}"));
    }

    // ---------------------------------------------------------------------------------------------------------------

    /** The two Jackson settings of application.yml that change the wire format: ISO dates, nulls kept. */
    private static ObjectMapper productionLikeMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm() {
        return post("/packs/orders/" + token + "/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":\"987654\"}");
    }

    private PackOrder order(PackOrderStatus status, PackEdition edition) {
        PackOrder order = new PackOrder();
        order.setId(42L);
        order.setStatus(status);
        order.setAccessToken(token);
        order.setEdition(edition);
        order.setBuyerName("ana gómez");
        order.setEmail("ana@example.com");
        order.setCatalogVersion("v1");
        order.setTotalAmount(new BigDecimal("14990"));
        order.setPaidAt(NOW.minusSeconds(3600));
        order.setAccessExpiresAt(NOW.plusSeconds(3600));
        lenient().when(orders.findByAccessTokenAndActivoTrue(token)).thenReturn(Optional.of(order));
        lenient().when(orders.findWithLockById(42L)).thenReturn(Optional.of(order));
        return order;
    }

    private void assertBody(String fixture, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        String expected = new String(new ClassPathResource("contract/" + fixture).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String actual = mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JSONAssert.assertEquals("Response differs from contract/" + fixture, expected, actual, true);
    }
}
