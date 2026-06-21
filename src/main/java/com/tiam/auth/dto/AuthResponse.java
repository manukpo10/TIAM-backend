package com.tiam.auth.dto;

import com.tiam.subscription.dto.SubscriptionResponse;
import com.tiam.user.dto.UserResponse;

public record AuthResponse(
        String token,
        UserResponse user,
        SubscriptionResponse subscription
) {}
