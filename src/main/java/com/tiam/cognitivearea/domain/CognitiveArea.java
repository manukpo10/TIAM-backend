package com.tiam.cognitivearea.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "cognitive_areas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CognitiveArea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;
}
