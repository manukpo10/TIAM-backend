package com.tiam.pack.web;

import com.tiam.common.web.ApiResponse;
import com.tiam.common.web.ClientIpResolver;
import com.tiam.pack.dto.RegretReceiptResponse;
import com.tiam.pack.dto.RegretRequest;
import com.tiam.pack.service.PackRegretService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The regret ("arrepentimiento") form. Public: it is reachable from the footer of every landing page. */
@RestController
@RequestMapping("/packs/regret")
@RequiredArgsConstructor
public class PackRegretController {

    private final PackRegretService regretService;
    private final ClientIpResolver clientIp;

    @PostMapping
    public ResponseEntity<ApiResponse<RegretReceiptResponse>> submit(
            @Valid @RequestBody RegretRequest request, HttpServletRequest http) {
        RegretReceiptResponse receipt = regretService.submit(request, clientIp.resolve(http));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(receipt));
    }
}
