package com.tiam.exercise.domain;

import com.tiam.cognitivearea.domain.CognitiveArea;
import com.tiam.common.audit.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "exercises")
@Getter
@Setter
public class Exercise extends BaseEntity {

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DifficultyLevel difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "material_type", nullable = false, length = 50)
    private MaterialType materialType;

    @Column(name = "file_url", length = 500)
    private String fileUrl;

    @Column(name = "preview_image_url", length = 500)
    private String previewImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ExerciseStatus status = ExerciseStatus.PUBLISHED;

    @Column(name = "owner_id")
    private Long ownerId;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "exercise_cognitive_areas",
        joinColumns = @JoinColumn(name = "exercise_id"),
        inverseJoinColumns = @JoinColumn(name = "cognitive_area_id")
    )
    private Set<CognitiveArea> cognitiveAreas = new HashSet<>();
}
