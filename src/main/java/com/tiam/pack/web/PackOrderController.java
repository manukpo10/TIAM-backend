package com.tiam.pack.web;

import com.tiam.common.web.ApiResponse;
import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackOrderStatus;
import com.tiam.pack.dto.ConfirmPaymentRequest;
import com.tiam.pack.dto.DownloadLinkResponse;
import com.tiam.pack.dto.OrderStatusResponse;
import com.tiam.pack.dto.PackOrderResponse;
import com.tiam.pack.service.PackDownloadService;
import com.tiam.pack.service.PackPaymentConfirmationService;
import com.tiam.pack.service.PackRateLimitedException;
import com.tiam.pack.service.PackRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token-in-the-URL endpoints for a buyer's own order. Public (no account): the 256-bit token is the
 * credential, so every response is {@code no-store} / {@code no-referrer} and every call is rate limited
 * per client IP.
 */
@RestController
@RequestMapping("/packs/orders")
@RequiredArgsConstructor
public class PackOrderController {

    private final PackPaymentConfirmationService confirmationService;
    private final PackDownloadService downloadService;
    private final PackRateLimiter rateLimiter;
    private final PackProperties properties;

    /**
     * The downloads / thank-you page. PAID with a valid access window returns the full body; PENDING and
     * FAILED return only their status; anything else (unknown, revoked, expired) is one uniform 404.
     */
    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<PackOrderResponse>> get(@PathVariable String token, HttpServletRequest http) {
        limit(http);
        return tokenResponse(downloadService.view(token));
    }

    /**
     * Issues a signed URL (valid a few minutes) for one file of the order and logs it. POST on purpose: link
     * scanners in email clients follow GETs and would burn the download caps without a person clicking.
     */
    @PostMapping("/{token}/files/{fileKey}/link")
    public ResponseEntity<ApiResponse<DownloadLinkResponse>> issueLink(
            @PathVariable String token, @PathVariable String fileKey, HttpServletRequest http) {
        limit(http);
        return tokenResponse(downloadService.issueLink(token, fileKey));
    }

    /**
     * The return page calls this once with the {@code payment_id} Mercado Pago put in the return URL. The
     * backend does not trust it: it re-reads the payment from Mercado Pago (see the service).
     */
    @PostMapping("/{token}/confirm")
    public ResponseEntity<ApiResponse<OrderStatusResponse>> confirm(
            @PathVariable String token,
            @Valid @RequestBody ConfirmPaymentRequest request,
            HttpServletRequest http) {
        limit(http);
        PackOrderStatus status = confirmationService.confirm(token, request.paymentId());
        return tokenResponse(new OrderStatusResponse(publicStatus(status)));
    }

    /** What a buyer's browser may learn: REVIEW looks like PENDING ("we are confirming your payment"). */
    static String publicStatus(PackOrderStatus status) {
        return switch (status) {
            case PAID -> "PAID";
            case FAILED -> "FAILED";
            case PENDING, REVIEW -> "PENDING";
            case REVOKED -> "PENDING"; // never reached: revoked orders answer 404 before this point
        };
    }

    private void limit(HttpServletRequest http) {
        boolean allowed = rateLimiter.tryAcquire("token-ip:" + http.getRemoteAddr(),
                properties.getRateLimits().getTokenCallsPerIp(), Duration.ofMinutes(1));
        if (!allowed) {
            throw new PackRateLimitedException();
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> tokenResponse(T data) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .body(ApiResponse.ok(data));
    }
}
