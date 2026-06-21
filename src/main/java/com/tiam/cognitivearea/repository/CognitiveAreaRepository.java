package com.tiam.cognitivearea.repository;

import com.tiam.cognitivearea.domain.CognitiveArea;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CognitiveAreaRepository extends JpaRepository<CognitiveArea, Long> {
    Optional<CognitiveArea> findBySlug(String slug);
}
