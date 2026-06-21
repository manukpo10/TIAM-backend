package com.tiam.exercise.mapper;

import com.tiam.cognitivearea.domain.CognitiveArea;
import com.tiam.cognitivearea.dto.CognitiveAreaResponse;
import com.tiam.cognitivearea.mapper.CognitiveAreaMapper;
import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.domain.Exercise;
import com.tiam.exercise.domain.ExerciseStatus;
import com.tiam.exercise.domain.MaterialType;
import com.tiam.exercise.dto.AdminExerciseRequest;
import com.tiam.exercise.dto.CreateOwnExerciseRequest;
import com.tiam.exercise.dto.ExerciseResponse;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.processing.Generated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-21T04:32:04-0300",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.7 (Oracle Corporation)"
)
@Component
public class ExerciseMapperImpl implements ExerciseMapper {

    @Autowired
    private CognitiveAreaMapper cognitiveAreaMapper;

    @Override
    public ExerciseResponse toResponse(Exercise exercise) {
        if ( exercise == null ) {
            return null;
        }

        Long id = null;
        String title = null;
        String description = null;
        String instructions = null;
        DifficultyLevel difficulty = null;
        MaterialType materialType = null;
        String fileUrl = null;
        String previewImageUrl = null;
        ExerciseStatus status = null;
        Set<CognitiveAreaResponse> cognitiveAreas = null;
        Long ownerId = null;
        Instant createdAt = null;
        Instant updatedAt = null;

        id = exercise.getId();
        title = exercise.getTitle();
        description = exercise.getDescription();
        instructions = exercise.getInstructions();
        difficulty = exercise.getDifficulty();
        materialType = exercise.getMaterialType();
        fileUrl = exercise.getFileUrl();
        previewImageUrl = exercise.getPreviewImageUrl();
        status = exercise.getStatus();
        cognitiveAreas = cognitiveAreaSetToCognitiveAreaResponseSet( exercise.getCognitiveAreas() );
        ownerId = exercise.getOwnerId();
        createdAt = exercise.getCreatedAt();
        updatedAt = exercise.getUpdatedAt();

        ExerciseResponse exerciseResponse = new ExerciseResponse( id, title, description, instructions, difficulty, materialType, fileUrl, previewImageUrl, status, cognitiveAreas, ownerId, createdAt, updatedAt );

        return exerciseResponse;
    }

    @Override
    public Exercise toEntity(CreateOwnExerciseRequest request) {
        if ( request == null ) {
            return null;
        }

        Exercise exercise = new Exercise();

        exercise.setTitle( request.title() );
        exercise.setDescription( request.description() );
        exercise.setInstructions( request.instructions() );
        exercise.setDifficulty( request.difficulty() );
        exercise.setMaterialType( request.materialType() );

        exercise.setStatus( ExerciseStatus.PUBLISHED );

        return exercise;
    }

    @Override
    public Exercise toEntity(AdminExerciseRequest request) {
        if ( request == null ) {
            return null;
        }

        Exercise exercise = new Exercise();

        exercise.setTitle( request.title() );
        exercise.setDescription( request.description() );
        exercise.setInstructions( request.instructions() );
        exercise.setDifficulty( request.difficulty() );
        exercise.setMaterialType( request.materialType() );
        exercise.setStatus( request.status() );

        return exercise;
    }

    @Override
    public void updateEntity(AdminExerciseRequest request, Exercise exercise) {
        if ( request == null ) {
            return;
        }

        if ( request.title() != null ) {
            exercise.setTitle( request.title() );
        }
        if ( request.description() != null ) {
            exercise.setDescription( request.description() );
        }
        if ( request.instructions() != null ) {
            exercise.setInstructions( request.instructions() );
        }
        if ( request.difficulty() != null ) {
            exercise.setDifficulty( request.difficulty() );
        }
        if ( request.materialType() != null ) {
            exercise.setMaterialType( request.materialType() );
        }
        if ( request.status() != null ) {
            exercise.setStatus( request.status() );
        }
    }

    protected Set<CognitiveAreaResponse> cognitiveAreaSetToCognitiveAreaResponseSet(Set<CognitiveArea> set) {
        if ( set == null ) {
            return null;
        }

        Set<CognitiveAreaResponse> set1 = LinkedHashSet.newLinkedHashSet( set.size() );
        for ( CognitiveArea cognitiveArea : set ) {
            set1.add( cognitiveAreaMapper.toResponse( cognitiveArea ) );
        }

        return set1;
    }
}
