package com.tiam.play.service;

import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.home.domain.HomeExerciseResult;
import com.tiam.home.domain.HomeExerciseType;
import com.tiam.home.repository.HomeExerciseResultRepository;
import com.tiam.home.service.HomeSubscriptionService;
import com.tiam.patient.domain.Patient;
import com.tiam.patient.repository.PatientRepository;
import com.tiam.play.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlayService {

    private static final String EXERCISE_TITLE = "Memoria";
    private static final String EXERCISE_INSTRUCTIONS =
            "Encontrá las parejas. Tocá dos tarjetas para darlas vuelta.";

    private final PatientRepository patientRepository;
    private final HomeSubscriptionService homeSubscriptionService;
    private final HomeExerciseResultRepository homeExerciseResultRepository;
    private final MemoryCardGenerator memoryCardGenerator;

    @Transactional(readOnly = true)
    public PlaySessionResponse getSession(String token) {
        Patient patient = resolveByToken(token);
        boolean subscriptionActive = homeSubscriptionService.isActive(patient.getId());

        MemoryExercise exercise = new MemoryExercise(
            "MEMORY_PAIRS",
            EXERCISE_TITLE,
            EXERCISE_INSTRUCTIONS,
            memoryCardGenerator.generate()
        );

        String firstName = patient.getFullName().split("\\s+")[0];
        return new PlaySessionResponse(firstName, exercise, subscriptionActive);
    }

    @Transactional
    public void recordResult(String token, CompletePlayRequest request) {
        Patient patient = resolveByToken(token);

        HomeExerciseResult result = new HomeExerciseResult();
        result.setPatient(patient);
        result.setExerciseType(HomeExerciseType.MEMORY_PAIRS);
        result.setExerciseTitle(EXERCISE_TITLE);
        result.setCompletedAt(Instant.now());
        result.setCompleted(true);
        result.setMoves(request.moves());
        result.setDurationSeconds(request.durationSeconds());

        homeExerciseResultRepository.save(result);
    }

    @Transactional
    public void activateSubscription(String token) {
        Patient patient = resolveByToken(token);
        homeSubscriptionService.activate(patient);
    }

    @Transactional(readOnly = true)
    public List<HomeExerciseResultResponse> getResultsForPatient(Long patientId, Long professionalId) {
        // Ownership check — patient must belong to this professional
        patientRepository.findByIdAndProfessionalIdAndActivoTrue(patientId, professionalId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + patientId));

        return homeExerciseResultRepository
            .findByPatientIdAndActivoTrueOrderByCompletedAtDesc(patientId)
            .stream()
            .map(r -> new HomeExerciseResultResponse(
                r.getId(),
                r.getExerciseType().name(),
                r.getExerciseTitle(),
                r.getCompletedAt(),
                r.isCompleted(),
                r.getMoves(),
                r.getDurationSeconds()
            ))
            .toList();
    }

    private Patient resolveByToken(String token) {
        return patientRepository.findByPlayTokenAndActivoTrue(token)
            .orElseThrow(() -> new ResourceNotFoundException("Invalid play token"));
    }
}
