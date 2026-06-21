package com.tiam.session.repository;

import com.tiam.session.domain.PatientSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface PatientSessionRepository extends JpaRepository<PatientSession, Long> {

    @Query("""
        SELECT s FROM PatientSession s
        LEFT JOIN FETCH s.exercises se
        LEFT JOIN FETCH se.cognitiveAreaSlugs
        WHERE s.patient.id = :patientId
          AND s.professionalId = :professionalId
          AND s.activo = true
        ORDER BY s.scheduledDate DESC
        """)
    List<PatientSession> findByPatientIdAndProfessionalId(
        @Param("patientId") Long patientId,
        @Param("professionalId") Long professionalId
    );
}
