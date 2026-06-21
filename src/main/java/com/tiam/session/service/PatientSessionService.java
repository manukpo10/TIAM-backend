package com.tiam.session.service;

import com.tiam.patient.domain.Patient;
import com.tiam.patient.service.PatientService;
import com.tiam.security.SecurityUtils;
import com.tiam.session.domain.PatientSession;
import com.tiam.session.domain.SessionExercise;
import com.tiam.session.domain.SessionStatus;
import com.tiam.session.dto.*;
import com.tiam.session.mapper.PatientSessionMapper;
import com.tiam.session.repository.PatientSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PatientSessionService {

    private final PatientSessionRepository patientSessionRepository;
    private final PatientSessionMapper patientSessionMapper;
    private final PatientService patientService;

    @Transactional(readOnly = true)
    public List<PatientSessionResponse> findByPatient(Long patientId) {
        Long userId = SecurityUtils.currentUserId();
        // Verify patient ownership
        patientService.findEntityById(patientId, userId);
        List<PatientSession> sessions = patientSessionRepository
            .findByPatientIdAndProfessionalId(patientId, userId);
        return patientSessionMapper.toResponseList(sessions);
    }

    @Transactional
    public PatientSessionResponse create(CreateSessionRequest request) {
        Long userId = SecurityUtils.currentUserId();
        Patient patient = patientService.findEntityById(request.patientId(), userId);

        PatientSession session = new PatientSession();
        session.setPatient(patient);
        session.setProfessionalId(userId);
        session.setTitle(request.title());
        session.setNotes(request.notes());
        session.setScheduledDate(Instant.now());
        session.setStatus(SessionStatus.COMPLETED);

        for (SessionExerciseDto dto : request.exercises()) {
            SessionExercise se = new SessionExercise();
            se.setSession(session);
            se.setExerciseId(dto.exerciseId());
            se.setTitle(dto.title());
            se.setDifficulty(dto.difficulty());
            se.setMaterialType(dto.materialType());
            if (dto.cognitiveAreaSlugs() != null) {
                se.getCognitiveAreaSlugs().addAll(dto.cognitiveAreaSlugs());
            }
            session.getExercises().add(se);
        }

        PatientSession saved = patientSessionRepository.save(session);
        // Update lastSessionAt on patient
        patient.setLastSessionAt(saved.getScheduledDate());

        return patientSessionMapper.toResponse(saved);
    }
}
