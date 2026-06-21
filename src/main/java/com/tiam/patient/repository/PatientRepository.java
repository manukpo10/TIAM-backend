package com.tiam.patient.repository;

import com.tiam.patient.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface PatientRepository
        extends JpaRepository<Patient, Long>, JpaSpecificationExecutor<Patient> {

    Optional<Patient> findByIdAndProfessionalIdAndActivoTrue(Long id, Long professionalId);

    Optional<Patient> findByPlayTokenAndActivoTrue(String playToken);
}
