package com.tiam.session.domain;

import com.tiam.common.audit.BaseEntity;
import com.tiam.patient.domain.Patient;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "patient_sessions")
@Getter
@Setter
public class PatientSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Column(nullable = false)
    private String title;

    @Column(name = "scheduled_date", nullable = false)
    private Instant scheduledDate;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SessionStatus status = SessionStatus.COMPLETED;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<SessionExercise> exercises = new HashSet<>();
}
