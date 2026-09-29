package com.tiam.challenge.web;

import com.tiam.challenge.config.ChallengeAdminProperties;
import com.tiam.challenge.dto.AdminGrantPurchaseRequest;
import com.tiam.challenge.dto.AdminGrantPurchaseResponse;
import com.tiam.challenge.service.ChallengePurchaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

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

    ChallengePurchaseController controller;

    @BeforeEach
    void setUp() {
        controller = new ChallengePurchaseController(challengePurchaseService, challengeAdminProperties);
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
