package com.tiam.subscription.dto;

import com.tiam.subscription.domain.ProfessionalPlan;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
        @NotNull ProfessionalPlan plan
) {}
