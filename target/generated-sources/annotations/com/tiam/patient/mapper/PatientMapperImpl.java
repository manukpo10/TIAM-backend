package com.tiam.patient.mapper;

import com.tiam.patient.domain.Patient;
import com.tiam.patient.dto.CreatePatientRequest;
import com.tiam.patient.dto.PatientResponse;
import com.tiam.patient.dto.UpdatePatientRequest;
import java.time.Instant;
import java.time.LocalDate;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-21T04:32:04-0300",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.7 (Oracle Corporation)"
)
@Component
public class PatientMapperImpl implements PatientMapper {

    @Override
    public PatientResponse toResponse(Patient patient, boolean homeSubscriptionActive) {
        if ( patient == null ) {
            return null;
        }

        Long id = null;
        String fullName = null;
        LocalDate birthDate = null;
        String diagnosis = null;
        String notes = null;
        Long professionalId = null;
        Instant createdAt = null;
        Instant lastSessionAt = null;
        String playToken = null;
        if ( patient != null ) {
            id = patient.getId();
            fullName = patient.getFullName();
            birthDate = patient.getBirthDate();
            diagnosis = patient.getDiagnosis();
            notes = patient.getNotes();
            professionalId = patient.getProfessionalId();
            createdAt = patient.getCreatedAt();
            lastSessionAt = patient.getLastSessionAt();
            playToken = patient.getPlayToken();
        }
        boolean homeSubscriptionActive1 = false;
        homeSubscriptionActive1 = homeSubscriptionActive;

        PatientResponse patientResponse = new PatientResponse( id, fullName, birthDate, diagnosis, notes, professionalId, createdAt, lastSessionAt, homeSubscriptionActive1, playToken );

        return patientResponse;
    }

    @Override
    public Patient toEntity(CreatePatientRequest request) {
        if ( request == null ) {
            return null;
        }

        Patient patient = new Patient();

        patient.setFullName( request.fullName() );
        patient.setBirthDate( request.birthDate() );
        patient.setDiagnosis( request.diagnosis() );
        patient.setNotes( request.notes() );

        return patient;
    }

    @Override
    public void updateEntity(UpdatePatientRequest request, Patient patient) {
        if ( request == null ) {
            return;
        }

        if ( request.fullName() != null ) {
            patient.setFullName( request.fullName() );
        }
        if ( request.birthDate() != null ) {
            patient.setBirthDate( request.birthDate() );
        }
        if ( request.diagnosis() != null ) {
            patient.setDiagnosis( request.diagnosis() );
        }
        if ( request.notes() != null ) {
            patient.setNotes( request.notes() );
        }
    }
}
