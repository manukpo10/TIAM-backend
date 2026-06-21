package com.tiam.exercise.web;

import com.tiam.common.web.ApiResponse;
import com.tiam.common.web.PagedResponse;
import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.dto.*;
import com.tiam.exercise.service.ExerciseService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/exercises")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class ExerciseController {

    private final ExerciseService exerciseService;

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ExerciseResponse>>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) DifficultyLevel difficulty,
            @RequestParam(required = false) String areas,
            @RequestParam(required = false) String owner) {
        List<String> areaSlugs = areas != null ? Arrays.asList(areas.split(",")) : null;
        PagedResponse<ExerciseResponse> result = exerciseService.findPublicLibrary(
            q, difficulty, areaSlugs, owner, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ExerciseResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(exerciseService.findPublicById(id)));
    }

    @PostMapping("/mine")
    public ResponseEntity<ApiResponse<ExerciseResponse>> createOwn(
            @Valid @RequestBody CreateOwnExerciseRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.ok(exerciseService.createOwn(request)));
    }
}
