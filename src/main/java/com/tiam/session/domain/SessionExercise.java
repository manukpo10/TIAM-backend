package com.tiam.session.domain;

import jakarta.persistence.*;
import lombok.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "session_exercises")
@Getter
@Setter
@NoArgsConstructor
public class SessionExercise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private PatientSession session;

    @Column(name = "exercise_id", nullable = false)
    private Long exerciseId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, length = 50)
    private String difficulty;

    @Column(name = "material_type", nullable = false, length = 50)
    private String materialType;

    @ElementCollection
    @CollectionTable(
        name = "session_exercise_area_slugs",
        joinColumns = @JoinColumn(name = "session_exercise_id")
    )
    @Column(name = "slug", length = 100)
    private Set<String> cognitiveAreaSlugs = new HashSet<>();
}
