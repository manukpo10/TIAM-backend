package com.tiam.exercise.service;

import com.tiam.cognitivearea.domain.CognitiveArea;
import com.tiam.cognitivearea.service.CognitiveAreaService;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.common.web.PagedResponse;
import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.domain.Exercise;
import com.tiam.exercise.domain.ExerciseStatus;
import com.tiam.exercise.dto.*;
import com.tiam.exercise.mapper.ExerciseMapper;
import com.tiam.exercise.repository.ExerciseRepository;
import com.tiam.exercise.repository.ExerciseSpecifications;
import com.tiam.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ExerciseService {

    private final ExerciseRepository exerciseRepository;
    private final ExerciseMapper exerciseMapper;
    private final CognitiveAreaService cognitiveAreaService;

    @Transactional(readOnly = true)
    public PagedResponse<ExerciseResponse> findPublicLibrary(
            String q, DifficultyLevel difficulty, List<String> areaSlugs, String owner,
            Pageable pageable) {
        Long userId = SecurityUtils.currentUserId();

        Specification<Exercise> spec = ExerciseSpecifications.isActive()
            .and(ExerciseSpecifications.hasStatus(ExerciseStatus.PUBLISHED))
            .and(ExerciseSpecifications.visibleToUser(userId));

        if (q != null && !q.isBlank()) {
            spec = spec.and(ExerciseSpecifications.titleContains(q));
        }
        if (difficulty != null) {
            spec = spec.and(ExerciseSpecifications.hasDifficulty(difficulty));
        }
        if (owner != null && !owner.isBlank()) {
            Specification<Exercise> ownerSpec = ExerciseSpecifications.ownerFilter(owner, userId);
            if (ownerSpec != null) {
                spec = spec.and(ownerSpec);
            }
        }
        if (areaSlugs != null && !areaSlugs.isEmpty()) {
            spec = spec.and(ExerciseSpecifications.hasAllAreaSlugs(areaSlugs));
        }

        Page<Exercise> page = exerciseRepository.findAll(spec, pageable);
        return PagedResponse.from(page.map(exerciseMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public ExerciseResponse findPublicById(Long id) {
        Long userId = SecurityUtils.currentUserId();
        Exercise exercise = exerciseRepository.findByIdAndActivoTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException("Exercise not found: " + id));
        if (exercise.getStatus() != ExerciseStatus.PUBLISHED ||
            (exercise.getOwnerId() != null && !exercise.getOwnerId().equals(userId))) {
            throw new ResourceNotFoundException("Exercise not found: " + id);
        }
        return exerciseMapper.toResponse(exercise);
    }

    @Transactional
    public ExerciseResponse createOwn(CreateOwnExerciseRequest request) {
        Long userId = SecurityUtils.currentUserId();
        Exercise exercise = exerciseMapper.toEntity(request);
        exercise.setOwnerId(userId);
        exercise.setStatus(ExerciseStatus.PUBLISHED);
        exercise.setCognitiveAreas(resolveAreas(request.cognitiveAreaIds()));
        return exerciseMapper.toResponse(exerciseRepository.save(exercise));
    }

    // --- Admin operations ---

    @Transactional(readOnly = true)
    public PagedResponse<ExerciseResponse> findAdminCatalog(
            String q, DifficultyLevel difficulty, List<String> areaSlugs, Pageable pageable) {

        Specification<Exercise> spec = ExerciseSpecifications.isActive()
            .and(ExerciseSpecifications.isTiamOwned());

        if (q != null && !q.isBlank()) {
            spec = spec.and(ExerciseSpecifications.titleContains(q));
        }
        if (difficulty != null) {
            spec = spec.and(ExerciseSpecifications.hasDifficulty(difficulty));
        }
        if (areaSlugs != null && !areaSlugs.isEmpty()) {
            spec = spec.and(ExerciseSpecifications.hasAllAreaSlugs(areaSlugs));
        }

        Page<Exercise> page = exerciseRepository.findAll(spec, pageable);
        return PagedResponse.from(page.map(exerciseMapper::toResponse));
    }

    @Transactional(readOnly = true)
    public ExerciseResponse findAdminById(Long id) {
        Exercise exercise = exerciseRepository.findByIdAndActivoTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException("Exercise not found: " + id));
        return exerciseMapper.toResponse(exercise);
    }

    @Transactional
    public ExerciseResponse createTiam(AdminExerciseRequest request) {
        Exercise exercise = exerciseMapper.toEntity(request);
        exercise.setOwnerId(null); // TIAM catalog
        if (request.status() != null) exercise.setStatus(request.status());
        exercise.setCognitiveAreas(resolveAreas(request.cognitiveAreaIds()));
        return exerciseMapper.toResponse(exerciseRepository.save(exercise));
    }

    @Transactional
    public ExerciseResponse updateTiam(Long id, AdminExerciseRequest request) {
        Exercise exercise = exerciseRepository.findByIdAndActivoTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException("Exercise not found: " + id));
        exerciseMapper.updateEntity(request, exercise);
        if (request.cognitiveAreaIds() != null && !request.cognitiveAreaIds().isEmpty()) {
            exercise.setCognitiveAreas(resolveAreas(request.cognitiveAreaIds()));
        }
        return exerciseMapper.toResponse(exercise);
    }

    @Transactional
    public void deleteTiam(Long id) {
        Exercise exercise = exerciseRepository.findByIdAndActivoTrue(id)
            .orElseThrow(() -> new ResourceNotFoundException("Exercise not found: " + id));
        exercise.setActivo(false); // soft delete — dirty-checking persists
    }

    private Set<CognitiveArea> resolveAreas(List<Long> ids) {
        Set<CognitiveArea> areas = new HashSet<>();
        for (Long areaId : ids) {
            areas.add(cognitiveAreaService.findEntityById(areaId));
        }
        return areas;
    }
}
