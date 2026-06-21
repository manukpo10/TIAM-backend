package com.tiam.user.dto;

import com.tiam.user.domain.Role;

public record UserResponse(
        Long id,
        String email,
        String fullName,
        String specialty,
        Role role
) {}
