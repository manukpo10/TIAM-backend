package com.tiam.subscription.web;

import com.tiam.common.web.ApiResponse;
import com.tiam.security.SecurityUtils;
import com.tiam.subscription.dto.CheckoutRequest;
import com.tiam.subscription.dto.CheckoutResponse;
import com.tiam.subscription.dto.SubscriptionResponse;
import com.tiam.subscription.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/subscription")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Subscription", description = "Professional subscription management")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping
    @Operation(summary = "Get current user's subscription status")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getSubscription() {
        Long userId = SecurityUtils.currentUserId();
        return ResponseEntity.ok(ApiResponse.ok(subscriptionService.getByUser(userId)));
    }

    @PostMapping("/checkout")
    @Operation(summary = "Create Mercado Pago checkout for professional plan")
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(
            @Valid @RequestBody CheckoutRequest request) {
        Long userId = SecurityUtils.currentUserId();
        CheckoutResponse response = subscriptionService.createCheckout(userId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
