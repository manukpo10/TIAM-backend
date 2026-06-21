package com.tiam.subscription.dto;

import com.tiam.subscription.domain.SubscriptionStatus;
import java.time.Instant;

public record SubscriptionResponse(
        Long id,
        SubscriptionStatus status,
        Instant trialEndsAt,
        Instant currentPeriodEnd
) {}
