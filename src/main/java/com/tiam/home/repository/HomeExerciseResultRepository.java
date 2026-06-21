package com.tiam.home.repository;

import com.tiam.home.domain.HomeExerciseResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HomeExerciseResultRepository extends JpaRepository<HomeExerciseResult, Long> {
    List<HomeExerciseResult> findByPatientIdAndActivoTrueOrderByCompletedAtDesc(Long patientId);
}
