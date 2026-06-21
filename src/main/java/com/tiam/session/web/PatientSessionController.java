package com.tiam.session.web;

import com.tiam.common.web.ApiResponse;
import com.tiam.session.dto.*;
import com.tiam.session.service.PatientSessionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PatientSessionController {

    private final PatientSessionService patientSessionService;

    @GetMapping("/patients/{patientId}/sessions")
    public ResponseEntity<ApiResponse<List<PatientSessionResponse>>> findByPatient(
            @PathVariable Long patientId) {
        return ResponseEntity.ok(ApiResponse.ok(patientSessionService.findByPatient(patientId)));
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<PatientSessionResponse>> create(
            @Valid @RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(patientSessionService.create(request)));
    }
}
