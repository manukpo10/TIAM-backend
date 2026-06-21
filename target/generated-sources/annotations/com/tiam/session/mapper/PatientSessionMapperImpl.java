package com.tiam.session.mapper;

import com.tiam.patient.domain.Patient;
import com.tiam.session.domain.PatientSession;
import com.tiam.session.domain.SessionExercise;
import com.tiam.session.domain.SessionStatus;
import com.tiam.session.dto.PatientSessionResponse;
import com.tiam.session.dto.SessionExerciseDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-21T04:32:04-0300",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.7 (Oracle Corporation)"
)
@Component
public class PatientSessionMapperImpl implements PatientSessionMapper {

    @Override
    public PatientSessionResponse toResponse(PatientSession session) {
        if ( session == null ) {
            return null;
        }

        Long patientId = null;
        Long id = null;
        Long professionalId = null;
        String title = null;
        Instant scheduledDate = null;
        String notes = null;
        SessionStatus status = null;

        patientId = sessionPatientId( session );
        id = session.getId();
        professionalId = session.getProfessionalId();
        title = session.getTitle();
        scheduledDate = session.getScheduledDate();
        notes = session.getNotes();
        status = session.getStatus();

        List<SessionExerciseDto> exercises = toExerciseDtoList(session.getExercises());

        PatientSessionResponse patientSessionResponse = new PatientSessionResponse( id, patientId, professionalId, title, exercises, scheduledDate, notes, status );

        return patientSessionResponse;
    }

    @Override
    public SessionExerciseDto toExerciseDto(SessionExercise sessionExercise) {
        if ( sessionExercise == null ) {
            return null;
        }

        Long exerciseId = null;
        String title = null;
        List<String> cognitiveAreaSlugs = null;
        String difficulty = null;
        String materialType = null;

        exerciseId = sessionExercise.getExerciseId();
        title = sessionExercise.getTitle();
        Set<String> set = sessionExercise.getCognitiveAreaSlugs();
        if ( set != null ) {
            cognitiveAreaSlugs = new ArrayList<String>( set );
        }
        difficulty = sessionExercise.getDifficulty();
        materialType = sessionExercise.getMaterialType();

        SessionExerciseDto sessionExerciseDto = new SessionExerciseDto( exerciseId, title, cognitiveAreaSlugs, difficulty, materialType );

        return sessionExerciseDto;
    }

    @Override
    public List<PatientSessionResponse> toResponseList(List<PatientSession> sessions) {
        if ( sessions == null ) {
            return null;
        }

        List<PatientSessionResponse> list = new ArrayList<PatientSessionResponse>( sessions.size() );
        for ( PatientSession patientSession : sessions ) {
            list.add( toResponse( patientSession ) );
        }

        return list;
    }

    private Long sessionPatientId(PatientSession patientSession) {
        Patient patient = patientSession.getPatient();
        if ( patient == null ) {
            return null;
        }
        return patient.getId();
    }
}
