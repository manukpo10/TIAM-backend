package com.tiam.patient.repository;

import com.tiam.patient.domain.Patient;
import org.springframework.data.jpa.domain.Specification;

public final class PatientSpecifications {

    private PatientSpecifications() {}

    public static Specification<Patient> isActive() {
        return (root, query, cb) -> cb.isTrue(root.get("activo"));
    }

    public static Specification<Patient> ownedByProfessional(Long professionalId) {
        return (root, query, cb) -> cb.equal(root.get("professionalId"), professionalId);
    }

    public static Specification<Patient> fullNameContains(String q) {
        String pattern = "%" + q.toLowerCase() + "%";
        return (root, query, cb) ->
            cb.like(cb.lower(root.get("fullName")), pattern);
    }
}
