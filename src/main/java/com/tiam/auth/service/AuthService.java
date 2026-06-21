package com.tiam.auth.service;

import com.tiam.auth.domain.PasswordResetToken;
import com.tiam.auth.dto.AuthResponse;
import com.tiam.auth.dto.ForgotPasswordRequest;
import com.tiam.auth.dto.LoginRequest;
import com.tiam.auth.dto.RegisterRequest;
import com.tiam.auth.dto.ResetPasswordRequest;
import com.tiam.auth.repository.PasswordResetTokenRepository;
import com.tiam.common.email.EmailService;
import com.tiam.common.exception.BadRequestException;
import com.tiam.security.JwtService;
import com.tiam.subscription.domain.Subscription;
import com.tiam.subscription.mapper.SubscriptionMapper;
import com.tiam.subscription.service.SubscriptionService;
import com.tiam.user.domain.Role;
import com.tiam.user.domain.User;
import com.tiam.user.mapper.UserMapper;
import com.tiam.user.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final SubscriptionService subscriptionService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final SubscriptionMapper subscriptionMapper;
    private final EmailService emailService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BadRequestException("Email already registered: " + request.email());
        }

        User user = new User();
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setSpecialty(request.specialty());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(Role.PROFESSIONAL);
        user = userRepository.save(user);

        Subscription subscription = subscriptionService.createTrial(user);
        String token = jwtService.generateToken(user);

        return new AuthResponse(
                token,
                userMapper.toResponse(user),
                subscriptionMapper.toResponse(subscription));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndActivoTrue(request.email())
                .orElseThrow(() -> new BadRequestException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException("Invalid email or password");
        }

        Subscription subscription = subscriptionService.findEntityByUserId(user.getId());
        String token = jwtService.generateToken(user);

        return new AuthResponse(
                token,
                userMapper.toResponse(user),
                subscriptionMapper.toResponse(subscription));
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        // Always succeed — never reveal whether the email exists
        userRepository.findByEmailAndActivoTrue(request.email()).ifPresent(user -> {
            PasswordResetToken resetToken = new PasswordResetToken();
            resetToken.setUser(user);
            resetToken.setToken(UUID.randomUUID().toString());
            resetToken.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
            resetToken.setUsed(false);
            passwordResetTokenRepository.save(resetToken);

            emailService.sendPasswordReset(user.getEmail(), resetToken.getToken());
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByTokenAndUsedFalse(request.token())
                .orElseThrow(() -> new BadRequestException("Invalid or expired reset token"));

        if (resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Reset token has expired");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);
    }
}
