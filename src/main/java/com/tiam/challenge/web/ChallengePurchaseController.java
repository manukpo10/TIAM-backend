package com.tiam.challenge.web;

import com.tiam.challenge.config.ChallengeAdminProperties;
import com.tiam.challenge.dto.AdminGrantPurchaseRequest;
import com.tiam.challenge.dto.AdminGrantPurchaseResponse;
import com.tiam.challenge.dto.ChallengeAccessResponse;
import com.tiam.challenge.dto.CreatePurchaseRequest;
import com.tiam.challenge.dto.CreatePurchaseResponse;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.web.ApiResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/challenge")
@RequiredArgsConstructor
public class ChallengePurchaseController {

    private final ChallengePurchaseService challengePurchaseService;
    private final ChallengeAdminProperties challengeAdminProperties;

    @PostMapping("/purchases")
    public ResponseEntity<ApiResponse<CreatePurchaseResponse>> createPurchase(
            @Valid @RequestBody CreatePurchaseRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(challengePurchaseService.createPurchase(request)));
    }

    @GetMapping("/{accessToken}")
    public ResponseEntity<ApiResponse<ChallengeAccessResponse>> getAccess(@PathVariable String accessToken) {
        return ResponseEntity.ok(ApiResponse.ok(challengePurchaseService.getAccess(accessToken)));
    }

    /**
     * Manually grants a challenge month to a phone that paid outside Mercado
     * Pago (transferencia, efectivo) — an operator tool, not a buyer-facing
     * endpoint. {@code /challenge/**} is {@code permitAll()} at the security
     * filter chain (the whole Desafío flow is unauthenticated, token-in-URL
     * instead of JWT), so this route checks its own shared secret rather
     * than relying on the filter chain — same reasoning as
     * {@code MercadoPagoWebhookController}'s own signature check.
     *
     * <p>Constant-time comparison ({@link MessageDigest#isEqual}) on
     * purpose: a naive {@code String.equals} short-circuits on the first
     * mismatched byte, which leaks how many leading characters an attacker
     * guessed right through response-timing differences.
     */
    @PostMapping("/admin/grants")
    public ResponseEntity<ApiResponse<AdminGrantPurchaseResponse>> grantManualPurchase(
            @RequestHeader("X-Admin-Secret") String providedSecret,
            @Valid @RequestBody AdminGrantPurchaseRequest request) {
        if (!isValidAdminSecret(providedSecret)) {
            throw new AccessDeniedException("Invalid admin secret");
        }
        return ResponseEntity.ok(ApiResponse.ok(challengePurchaseService.grantManualPurchase(request)));
    }

    private boolean isValidAdminSecret(String providedSecret) {
        String expectedSecret = challengeAdminProperties.getAdminGrantSecret();
        if (!StringUtils.hasText(expectedSecret)) {
            // Unconfigured means "not set up for this environment" — fail
            // closed. Unlike MP_WEBHOOK_SECRET's warn-and-allow fallback
            // (which guards inbound webhooks MP itself calls), this
            // endpoint grants real paid access; an unset secret must never
            // mean "anyone can call it."
            return false;
        }
        if (!StringUtils.hasText(providedSecret)) {
            return false;
        }
        byte[] expected = expectedSecret.getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedSecret.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }
}
