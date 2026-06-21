package com.tiam.exercise.repository;

import com.tiam.exercise.domain.Exercise;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExerciseRepository
        extends JpaRepository<Exercise, Long>, JpaSpecificationExecutor<Exercise> {

    @EntityGraph(attributePaths = {"cognitiveAreas"})
    Optional<Exercise> findByIdAndActivoTrue(Long id);

    /**
     * Fetch multiple active exercises with their cognitive areas in one query.
     * Used by the PDF batch endpoint to avoid N+1 when generating combined fichas.
     */
    @EntityGraph(attributePaths = {"cognitiveAreas"})
    @Query("SELECT e FROM Exercise e WHERE e.id IN :ids AND e.activo = true")
    List<Exercise> findAllByIdInAndActivoTrue(@Param("ids") List<Long> ids);
}
