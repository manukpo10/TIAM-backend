package com.tiam.patient.web;

import com.tiam.common.web.ApiResponse;
import com.tiam.common.web.PagedResponse;
import com.tiam.patient.dto.*;
import com.tiam.patient.service.PatientService;
import com.tiam.play.dto.HomeExerciseResultResponse;
import com.tiam.play.service.PlayService;
import com.tiam.security.SecurityUtils;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/patients")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PatientController {

    private final PatientService patientService;
    private final PlayService playService;

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<PatientResponse>>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(ApiResponse.ok(
            patientService.findAll(q, PageRequest.of(page, size))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PatientResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(patientService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PatientResponse>> create(
            @Valid @RequestBody CreatePatientRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(patientService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PatientResponse>> update(
            @PathVariable Long id, @Valid @RequestBody UpdatePatientRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(patientService.update(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        patientService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @GetMapping("/{id}/home-results")
    public ResponseEntity<ApiResponse<List<HomeExerciseResultResponse>>> getHomeResults(
            @PathVariable Long id) {
        Long professionalId = SecurityUtils.currentUserId();
        return ResponseEntity.ok(ApiResponse.ok(
            playService.getResultsForPatient(id, professionalId)));
    }
}
