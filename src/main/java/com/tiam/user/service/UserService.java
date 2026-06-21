package com.tiam.user.service;

import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.user.domain.User;
import com.tiam.user.dto.ChangePasswordRequest;
import com.tiam.user.dto.UpdateProfileRequest;
import com.tiam.user.dto.UserResponse;
import com.tiam.user.mapper.UserMapper;
import com.tiam.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        return userMapper.toResponse(findEntityById(id));
    }

    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return userRepository.findByEmailAndActivoTrue(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {
        Long userId = extractCurrentUserId();
        return getById(userId);
    }

    @Transactional
    public UserResponse updateProfile(UpdateProfileRequest request) {
        Long userId = extractCurrentUserId();
        User user = findEntityById(userId);
        user.setFullName(request.fullName());
        user.setSpecialty(request.specialty());
        return userMapper.toResponse(user);
    }

    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        Long userId = extractCurrentUserId();
        User user = findEntityById(userId);

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    }

    // --- package-visible helpers used by AuthService ---

    @Transactional(readOnly = true)
    public User findEntityById(Long id) {
        return userRepository.findById(id)
                .filter(User::isActivo)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    // --- private ---

    private Long extractCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        // Principal is set as the user id (Long) by JwtAuthFilter
        return (Long) principal;
    }
}
