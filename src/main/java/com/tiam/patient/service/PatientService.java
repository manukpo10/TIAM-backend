package com.tiam.patient.service;

import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.common.web.PagedResponse;
import com.tiam.home.service.HomeSubscriptionService;
import com.tiam.patient.domain.Patient;
import com.tiam.patient.dto.*;
import com.tiam.patient.mapper.PatientMapper;
import com.tiam.patient.repository.PatientRepository;
import com.tiam.patient.repository.PatientSpecifications;
import com.tiam.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;
    private final PatientMapper patientMapper;
    private final HomeSubscriptionService homeSubscriptionService;

    @Transactional(readOnly = true)
    public PagedResponse<PatientResponse> findAll(String q, Pageable pageable) {
        Long userId = SecurityUtils.currentUserId();

        Specification<Patient> spec = PatientSpecifications.isActive()
            .and(PatientSpecifications.ownedByProfessional(userId));

        if (q != null && !q.isBlank()) {
            spec = spec.and(PatientSpecifications.fullNameContains(q));
        }

        return PagedResponse.from(
            patientRepository.findAll(spec, pageable)
                .map(p -> toResponse(p)));
    }

    @Transactional(readOnly = true)
    public PatientResponse findById(Long id) {
        Long userId = SecurityUtils.currentUserId();
        Patient patient = findOwnedPatient(id, userId);
        return toResponse(patient);
    }

    @Transactional
    public PatientResponse create(CreatePatientRequest request) {
        Long userId = SecurityUtils.currentUserId();
        Patient patient = patientMapper.toEntity(request);
        patient.setProfessionalId(userId);
        patient.setPlayToken(UUID.randomUUID().toString());
        return toResponse(patientRepository.save(patient));
    }

    @Transactional
    public PatientResponse update(Long id, UpdatePatientRequest request) {
        Long userId = SecurityUtils.currentUserId();
        Patient patient = findOwnedPatient(id, userId);
        patientMapper.updateEntity(request, patient);
        return toResponse(patient);
    }

    @Transactional
    public void delete(Long id) {
        Long userId = SecurityUtils.currentUserId();
        Patient patient = findOwnedPatient(id, userId);
        patient.setActivo(false);
    }

    // Package-visible for use by SessionService
    @Transactional
    public Patient findEntityById(Long id, Long professionalId) {
        return findOwnedPatient(id, professionalId);
    }

    private PatientResponse toResponse(Patient patient) {
        boolean active = homeSubscriptionService.isActive(patient.getId());
        return patientMapper.toResponse(patient, active);
    }

    private Patient findOwnedPatient(Long id, Long professionalId) {
        return patientRepository.findByIdAndProfessionalIdAndActivoTrue(id, professionalId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found: " + id));
    }
}
