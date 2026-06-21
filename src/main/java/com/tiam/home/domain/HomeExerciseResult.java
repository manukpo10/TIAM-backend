package com.tiam.home.domain;

import com.tiam.common.audit.BaseEntity;
import com.tiam.patient.domain.Patient;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "home_exercise_results")
@Getter
@Setter
public class HomeExerciseResult extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(name = "exercise_type", nullable = false)
    private HomeExerciseType exerciseType;

    @Column(name = "exercise_title", nullable = false)
    private String exerciseTitle;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(nullable = false)
    private boolean completed;

    @Column(nullable = false)
    private int moves;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;
}
