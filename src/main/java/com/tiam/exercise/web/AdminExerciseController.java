package com.tiam.exercise.web;

import com.tiam.common.storage.SupabaseStorageService;
import com.tiam.common.web.ApiResponse;
import com.tiam.common.web.PagedResponse;
import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.dto.*;
import com.tiam.exercise.service.ExerciseService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/exercises")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('ADMIN')")
public class AdminExerciseController {

    private final ExerciseService exerciseService;
    private final SupabaseStorageService storageService;

    /** Upload an exercise image; returns its public URL to store on the exercise. */
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, String>>> uploadImage(
            @RequestParam("file") MultipartFile file) {
        String url = storageService.uploadImage(file);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ExerciseResponse>>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DifficultyLevel difficulty,
            @RequestParam(required = false) String areas) {
        List<String> areaSlugs = areas != null ? Arrays.asList(areas.split(",")) : null;
        return ResponseEntity.ok(ApiResponse.ok(
            exerciseService.findAdminCatalog(q, difficulty, areaSlugs, PageRequest.of(page, size))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ExerciseResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(exerciseService.findAdminById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ExerciseResponse>> create(
            @Valid @RequestBody AdminExerciseRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(exerciseService.createTiam(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ExerciseResponse>> update(
            @PathVariable Long id, @Valid @RequestBody AdminExerciseRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(exerciseService.updateTiam(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        exerciseService.deleteTiam(id);
        return ResponseEntity.noContent().build();
    }
}
