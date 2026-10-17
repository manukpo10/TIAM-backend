package com.tiam.lead.web;

import com.tiam.common.web.ApiResponse;
import com.tiam.common.web.ClientIpResolver;
import com.tiam.lead.dto.CreateLeadRequest;
import com.tiam.lead.dto.LeadResponse;
import com.tiam.lead.service.LeadService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/leads")
@RequiredArgsConstructor
public class LeadController {

    private final LeadService leadService;
    private final ClientIpResolver ipResolver;

    /**
     * Public — capture a lead from the "recursos gratuitos" lead magnet (and the platform waiting list).
     * Always the same 201, also when the service dropped a bot: the answer must not tell a bot which check failed.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> create(@Valid @RequestBody CreateLeadRequest request,
                                                    HttpServletRequest httpRequest) {
        leadService.capture(request, ipResolver.resolve(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(null, "¡Listo! Ya podés descargar las fichas."));
    }

    /** Admin — list captured leads (newest first). */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApiResponse<List<LeadResponse>>> findAll() {
        return ResponseEntity.ok(ApiResponse.ok(leadService.findAll()));
    }
}
