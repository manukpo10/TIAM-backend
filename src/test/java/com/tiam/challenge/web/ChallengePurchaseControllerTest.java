package com.tiam.challenge.web;

import com.tiam.challenge.config.ChallengeAdminProperties;
import com.tiam.challenge.dto.AdminGrantPurchaseRequest;
import com.tiam.challenge.dto.AdminGrantPurchaseResponse;
import com.tiam.challenge.dto.ChallengeAccessResponse;
import com.tiam.challenge.dto.ChallengeMonthAccess;
import com.tiam.challenge.dto.CreatePurchaseResponse;
import com.tiam.challenge.dto.NextMonthRequest;
import com.tiam.challenge.service.ChallengeDeviceService;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers only {@code /challenge/admin/grants}'s own secret gate — the actual
 * grant logic (idempotency, PAID status, link shape) is
 * {@link com.tiam.challenge.service.ChallengePurchaseServiceTest}'s job.
 * This is a manual-grant tool for a buyer who paid outside Mercado Pago, so
 * a broken gate here means either a locked-out operator or unauthorized free
 * access — same stakes as {@code MercadoPagoWebhookControllerTest}'s own
 * signature-validation coverage, which this mirrors.
 */
@ExtendWith(MockitoExtension.class)
class ChallengePurchaseControllerTest {

    private static final String SECRET = "test-admin-secret";

    @Mock ChallengePurchaseService challengePurchaseService;
    @Mock ChallengeAdminProperties challengeAdminProperties;
    @Mock ChallengeDeviceService challengeDeviceService;

    ChallengePurchaseController controller;

    @BeforeEach
    void setUp() {
        controller = new ChallengePurchaseController(challengePurchaseService, challengeAdminProperties,
                challengeDeviceService);
    }

    private static ChallengeAccessResponse anAccess() {
        return new ChallengeAccessResponse("Ana", 3, 30, 1,
                java.util.List.of(new ChallengeMonthAccess(1, "tok-1", 3, 30)), 2, 20000);
    }

    // --- POST /challenge/{token}/next-month: "Renovar y desbloquear el mes siguiente" ---------------

    @Test
    void nextMonth_returnsTheMercadoPagoCheckoutUrl() {
        when(challengePurchaseService.createNextMonthPurchase("tok-1", 2))
                .thenReturn(new CreatePurchaseResponse("https://mp.example/checkout"));

        var response = controller.createNextMonth("tok-1", new NextMonthRequest(2));

        assertThat(response.getBody().getData().checkoutUrl()).isEqualTo("https://mp.example/checkout");
    }

    /** The player says which month it is offering, so a stale page can never buy a different one. */
    @Test
    void nextMonth_overHttp_bindsTheOfferedMonth() throws Exception {
        when(challengePurchaseService.createNextMonthPurchase("tok-1", 2))
                .thenReturn(new CreatePurchaseResponse("https://mp.example/checkout"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/challenge/tok-1/next-month").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeMonth\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutUrl").value("https://mp.example/checkout"));
    }

    /** Nothing is typed in the player: no body at all is a valid request. */
    @Test
    void nextMonth_withoutABody_isStillAValidRequest() throws Exception {
        when(challengePurchaseService.createNextMonthPurchase("tok-1", null))
                .thenReturn(new CreatePurchaseResponse("https://mp.example/checkout"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/challenge/tok-1/next-month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutUrl").value("https://mp.example/checkout"));
    }

    // --- GET /challenge/{token}: access + device measurement (never blocks) ---------------------

    @Test
    void getAccess_returnsTheAccess_andRecordsWhichDeviceOpenedIt() {
        ChallengeAccessResponse access = anAccess();
        when(challengePurchaseService.getAccess("tok-1")).thenReturn(access);

        var response = controller.getAccess("tok-1", "8d6e4c2a-1b3f-4a5c-9e7d-2f4b6d8a0c1e", "UA");

        assertThat(response.getBody().getData()).isEqualTo(access);
        verify(challengeDeviceService).recordVisit("tok-1", "8d6e4c2a-1b3f-4a5c-9e7d-2f4b6d8a0c1e", "UA");
    }

    /** The real HTTP binding: the player sends the id as ?device= and the browser its User-Agent header. */
    @Test
    void getAccess_overHttp_bindsTheDeviceQueryParamAndTheUserAgent() throws Exception {
        when(challengePurchaseService.getAccess("tok-1")).thenReturn(anAccess());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/challenge/tok-1").param("device", "8d6e4c2a-1b3f-4a5c-9e7d-2f4b6d8a0c1e")
                        .header("User-Agent", "Mozilla/5.0 (iPhone)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.buyerFirstName").value("Ana"));

        verify(challengeDeviceService).recordVisit("tok-1", "8d6e4c2a-1b3f-4a5c-9e7d-2f4b6d8a0c1e", "Mozilla/5.0 (iPhone)");
    }

    /** During a deploy the async executor can refuse new work; the buyer must still get in. */
    @Test
    void getAccess_whenTheMeasurementCannotBeQueued_stillOpens() {
        ChallengeAccessResponse access = anAccess();
        when(challengePurchaseService.getAccess("tok-1")).thenReturn(access);
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("executor shutting down"))
                .when(challengeDeviceService).recordVisit("tok-1", "8d6e4c2a-1b3f-4a5c-9e7d-2f4b6d8a0c1e", "UA");

        var response = controller.getAccess("tok-1", "8d6e4c2a-1b3f-4a5c-9e7d-2f4b6d8a0c1e", "UA");

        assertThat(response.getBody().getData()).isEqualTo(access);
    }

    @Test
    void getAccess_withoutADeviceId_stillOpens() {
        ChallengeAccessResponse access = anAccess();
        when(challengePurchaseService.getAccess("tok-1")).thenReturn(access);

        var response = controller.getAccess("tok-1", null, null);

        assertThat(response.getBody().getData()).isEqualTo(access);
    }

    @Test
    void getAccess_unknownOrUnpaidToken_recordsNothing() {
        when(challengePurchaseService.getAccess("nope"))
                .thenThrow(new ResourceNotFoundException("Challenge access not found"));

        assertThatThrownBy(() -> controller.getAccess("nope", "8d6e4c2a-1b3f-4a5c-9e7d-2f4b6d8a0c1e", "UA"))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(challengeDeviceService);
    }

    @Test
    void grantManualPurchase_correctSecret_delegatesToService() {
        when(challengeAdminProperties.getAdminGrantSecret()).thenReturn(SECRET);
        AdminGrantPurchaseRequest request = new AdminGrantPurchaseRequest("Amelia", "11 2233-4455", null, 1);
        AdminGrantPurchaseResponse expected =
                new AdminGrantPurchaseResponse("token-123", "http://localhost/desafio/token-123", "Amelia", 1);
        when(challengePurchaseService.grantManualPurchase(request)).thenReturn(expected);

        var response = controller.grantManualPurchase(SECRET, request);

        assertThat(response.getBody().getData()).isEqualTo(expected);
    }

    @Test
    void grantManualPurchase_wrongSecret_throwsAccessDeniedAndNeverCallsService() {
        when(challengeAdminProperties.getAdminGrantSecret()).thenReturn(SECRET);
        AdminGrantPurchaseRequest request = new AdminGrantPurchaseRequest("Amelia", "11 2233-4455", null, 1);

        assertThatThrownBy(() -> controller.grantManualPurchase("wrong-secret", request))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(challengePurchaseService);
    }

    @Test
    void grantManualPurchase_missingSecret_throwsAccessDenied() {
        when(challengeAdminProperties.getAdminGrantSecret()).thenReturn(SECRET);
        AdminGrantPurchaseRequest request = new AdminGrantPurchaseRequest("Amelia", "11 2233-4455", null, 1);

        assertThatThrownBy(() -> controller.grantManualPurchase("", request))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(challengePurchaseService);
    }

    @Test
    void grantManualPurchase_secretNotConfigured_failsClosedEvenWithNoHeaderSent() {
        // Deliberately the OPPOSITE of MP_WEBHOOK_SECRET's warn-and-allow
        // fallback: this endpoint grants real paid access, so an unset
        // secret must mean "nobody gets in," not "anyone does."
        when(challengeAdminProperties.getAdminGrantSecret()).thenReturn(null);
        AdminGrantPurchaseRequest request = new AdminGrantPurchaseRequest("Amelia", "11 2233-4455", null, 1);

        assertThatThrownBy(() -> controller.grantManualPurchase("anything", request))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(challengePurchaseService);
    }

    @Test
    void grantManualPurchase_secretConfigured_rejectsCaseMismatch() {
        // Constant-time comparison is byte-exact, not case-insensitive — a
        // near-miss must still be rejected, not accidentally accepted.
        when(challengeAdminProperties.getAdminGrantSecret()).thenReturn(SECRET);
        AdminGrantPurchaseRequest request = new AdminGrantPurchaseRequest("Amelia", "11 2233-4455", null, 1);

        assertThatThrownBy(() -> controller.grantManualPurchase(SECRET.toUpperCase(), request))
                .isInstanceOf(AccessDeniedException.class);

        verify(challengePurchaseService, never()).grantManualPurchase(request);
    }
}
