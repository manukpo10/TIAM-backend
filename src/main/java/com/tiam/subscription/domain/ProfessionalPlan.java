package com.tiam.subscription.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ProfessionalPlan {
    MONTHLY("Profesional Mensual", 20000.0, "$20.000 ARS/mes", 1, "months"),
    ANNUAL("Profesional Anual", 200000.0, "$200.000 ARS/año", 12, "months");

    private final String displayName;
    private final double amount;
    private final String label;
    private final int frequency;
    private final String frequencyType;
}
