package com.tiam.exercise.repository;

import com.tiam.exercise.domain.DifficultyLevel;
import com.tiam.exercise.domain.Exercise;
import com.tiam.exercise.domain.ExerciseStatus;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

public final class ExerciseSpecifications {

    private ExerciseSpecifications() {}

    public static Specification<Exercise> isActive() {
        return (root, query, cb) -> cb.isTrue(root.get("activo"));
    }

    public static Specification<Exercise> hasStatus(ExerciseStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * Visible to a given user: TIAM catalog (ownerId IS NULL) OR owned by userId.
     */
    public static Specification<Exercise> visibleToUser(Long userId) {
        return (root, query, cb) -> cb.or(
            cb.isNull(root.get("ownerId")),
            cb.equal(root.get("ownerId"), userId)
        );
    }

    /** ownerId IS NULL — TIAM-owned catalog exercises. */
    public static Specification<Exercise> isTiamOwned() {
        return (root, query, cb) -> cb.isNull(root.get("ownerId"));
    }

    public static Specification<Exercise> titleContains(String q) {
        String pattern = "%" + q.toLowerCase() + "%";
        return (root, query, cb) ->
            cb.like(cb.lower(root.get("title")), pattern);
    }

    public static Specification<Exercise> hasDifficulty(DifficultyLevel difficulty) {
        return (root, query, cb) -> cb.equal(root.get("difficulty"), difficulty);
    }

    /**
     * "mine" → ownerId = userId; "tiam" → ownerId IS NULL.
     * Returns null (no predicate) for any other value.
     */
    public static Specification<Exercise> ownerFilter(String owner, Long userId) {
        if ("mine".equalsIgnoreCase(owner)) {
            return (root, query, cb) -> cb.equal(root.get("ownerId"), userId);
        }
        if ("tiam".equalsIgnoreCase(owner)) {
            return (root, query, cb) -> cb.isNull(root.get("ownerId"));
        }
        return null;
    }

    /**
     * Exercises whose cognitiveAreas contain at least one of the given slugs.
     * Uses DISTINCT to avoid duplicates from the join.
     */
    public static Specification<Exercise> hasAreaSlugIn(List<String> slugs) {
        return (root, query, cb) -> {
            query.distinct(true);
            var areas = root.join("cognitiveAreas", JoinType.INNER);
            return areas.get("slug").in(slugs);
        };
    }
}
