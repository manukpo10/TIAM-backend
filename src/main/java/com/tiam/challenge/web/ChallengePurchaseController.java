package com.tiam.challenge.web;

import com.tiam.challenge.dto.CreatePurchaseRequest;
import com.tiam.challenge.dto.CreatePurchaseResponse;
import com.tiam.challenge.service.ChallengePurchaseService;
import com.tiam.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/challenge")
@RequiredArgsConstructor
public class ChallengePurchaseController {

    private final ChallengePurchaseService challengePurchaseService;

    @PostMapping("/purchases")
    public ResponseEntity<ApiResponse<CreatePurchaseResponse>> createPurchase(
            @Valid @RequestBody CreatePurchaseRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(challengePurchaseService.createPurchase(request)));
    }
}
