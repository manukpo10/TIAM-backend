package com.tiam.pack.web;

import com.tiam.common.web.ApiResponse;
import com.tiam.pack.dto.ResendAccessRequest;
import com.tiam.pack.service.PackAccessResendService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "I lost my link". Always answers 202 with the same message, whether or not the email bought anything, so
 * it cannot be used to find out who is a customer (see {@link PackAccessResendService}).
 */
@RestController
@RequestMapping("/packs/access")
@RequiredArgsConstructor
public class PackAccessController {

    static final String MESSAGE = "Si hay un pedido con ese email, te mandamos el acceso.";

    private final PackAccessResendService resendService;

    @PostMapping("/resend")
    public ResponseEntity<ApiResponse<Void>> resend(@Valid @RequestBody ResendAccessRequest request, HttpServletRequest http) {
        resendService.request(request.email(), http.getRemoteAddr());
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(null, MESSAGE));
    }
}
