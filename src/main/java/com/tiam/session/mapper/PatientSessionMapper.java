package com.tiam.session.mapper;

import com.tiam.session.domain.PatientSession;
import com.tiam.session.domain.SessionExercise;
import com.tiam.session.dto.PatientSessionResponse;
import com.tiam.session.dto.SessionExerciseDto;
import org.mapstruct.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PatientSessionMapper {

    @Mapping(source = "patient.id", target = "patientId")
    @Mapping(target = "exercises", expression = "java(toExerciseDtoList(session.getExercises()))")
    PatientSessionResponse toResponse(PatientSession session);

    @Mapping(source = "exerciseId", target = "exerciseId")
    SessionExerciseDto toExerciseDto(SessionExercise sessionExercise);

    List<PatientSessionResponse> toResponseList(List<PatientSession> sessions);

    default List<SessionExerciseDto> toExerciseDtoList(Set<SessionExercise> exercises) {
        if (exercises == null) return new ArrayList<>();
        List<SessionExerciseDto> result = new ArrayList<>(exercises.size());
        for (SessionExercise se : exercises) {
            result.add(toExerciseDto(se));
        }
        return result;
    }
}
