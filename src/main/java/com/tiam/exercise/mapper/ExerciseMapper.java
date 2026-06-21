package com.tiam.exercise.mapper;

import com.tiam.cognitivearea.mapper.CognitiveAreaMapper;
import com.tiam.exercise.domain.Exercise;
import com.tiam.exercise.dto.AdminExerciseRequest;
import com.tiam.exercise.dto.CreateOwnExerciseRequest;
import com.tiam.exercise.dto.ExerciseResponse;
import org.mapstruct.*;

@Mapper(componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {CognitiveAreaMapper.class})
public interface ExerciseMapper {

    ExerciseResponse toResponse(Exercise exercise);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "activo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "ownerId", ignore = true)
    @Mapping(target = "cognitiveAreas", ignore = true)
    @Mapping(target = "status", constant = "PUBLISHED")
    @Mapping(target = "fileUrl", ignore = true)
    @Mapping(target = "previewImageUrl", ignore = true)
    Exercise toEntity(CreateOwnExerciseRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "activo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "ownerId", ignore = true)
    @Mapping(target = "cognitiveAreas", ignore = true)
    @Mapping(target = "fileUrl", ignore = true)
    Exercise toEntity(AdminExerciseRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "activo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "ownerId", ignore = true)
    @Mapping(target = "cognitiveAreas", ignore = true)
    @Mapping(target = "fileUrl", ignore = true)
    void updateEntity(AdminExerciseRequest request, @MappingTarget Exercise exercise);
}
