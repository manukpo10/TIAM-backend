package com.tiam.patient.domain;

import com.tiam.common.audit.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "patients")
@Getter
@Setter
public class Patient extends BaseEntity {

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Column(nullable = true, length = 500)
    private String diagnosis;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "professional_id", nullable = false)
    private Long professionalId;

    @Column(name = "last_session_at")
    private Instant lastSessionAt;

    @Column(name = "play_token", unique = true)
    private String playToken;
}
