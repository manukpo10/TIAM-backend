package com.tiam.subscription.service;

import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.tiam.common.exception.BadRequestException;
import com.tiam.common.exception.ResourceNotFoundException;
import com.tiam.subscription.domain.ProfessionalPlan;
import com.tiam.subscription.domain.Subscription;
import com.tiam.subscription.domain.SubscriptionStatus;
import com.tiam.subscription.dto.CheckoutRequest;
import com.tiam.subscription.dto.CheckoutResponse;
import com.tiam.subscription.dto.SubscriptionResponse;
import com.tiam.subscription.mapper.SubscriptionMapper;
import com.tiam.subscription.repository.SubscriptionRepository;
import com.tiam.user.domain.User;
import com.tiam.user.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionMapper subscriptionMapper;
    private final MercadoPagoService mercadoPagoService;
    private final UserRepository userRepository;

    // -------------------------------------------------------------------------
    // Existing methods (unchanged)
    // -------------------------------------------------------------------------

    @Transactional
    public Subscription createTrial(User user) {
        Subscription subscription = new Subscription();
        subscription.setUser(user);
        subscription.setStatus(SubscriptionStatus.TRIAL);
        subscription.setTrialEndsAt(Instant.now().plus(7, ChronoUnit.DAYS));
        return subscriptionRepository.save(subscription);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse getByUser(Long userId) {
        Subscription subscription = subscriptionRepository.findByUserIdAndActivoTrue(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subscription not found for user: " + userId));
        return subscriptionMapper.toResponse(subscription);
    }

    @Transactional(readOnly = true)
    public Subscription findEntityByUserId(Long userId) {
        return subscriptionRepository.findByUserIdAndActivoTrue(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subscription not found for user: " + userId));
    }

    // -------------------------------------------------------------------------
    // Phase 3: Checkout + Webhook handlers
    // -------------------------------------------------------------------------

    /**
     * Creates a Mercado Pago preapproval and returns the init_point URL.
     * If MP is not configured, throws BadRequestException (returns 400 to the client).
     */
    @Transactional
    public CheckoutResponse createCheckout(Long userId, CheckoutRequest request) {
        if (!mercadoPagoService.isConfigured()) {
            throw new BadRequestException(
                    "Payment processing is not available yet. "
                            + "Please contact support to enable your subscription.");
        }

        Subscription subscription = findEntityByUserId(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        ProfessionalPlan plan = request.plan();
        String externalReference = String.valueOf(subscription.getId());

        try {
            String initPoint = mercadoPagoService.createPreapproval(
                    plan, user.getEmail(), externalReference);

            // Store plan so webhook can correlate
            subscription.setPlan(plan.name());
            // mpPreapprovalId will be set when the webhook confirms the preapproval id
            subscriptionRepository.save(subscription);

            return new CheckoutResponse(initPoint);
        } catch (MPException | MPApiException e) {
            log.error("Failed to create MP preapproval for userId={}: {}", userId, e.getMessage(), e);
            throw new BadRequestException(
                    "Could not create payment subscription: " + e.getMessage());
        }
    }

    /**
     * Called from the webhook handler when an MP preapproval is authorized/active.
     * Sets status=ACTIVE, currentPeriodEnd, and stores the mpPreapprovalId.
     */
    @Transactional
    public void activateSubscription(Long subscriptionId, String mpPreapprovalId,
            Instant currentPeriodEnd) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subscription not found: " + subscriptionId));
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setMpPreapprovalId(mpPreapprovalId);
        subscription.setCurrentPeriodEnd(currentPeriodEnd);
        subscriptionRepository.save(subscription);
        log.info("Activated subscription id={} via MP preapproval={}", subscriptionId, mpPreapprovalId);
    }

    /**
     * Called from the webhook handler when an MP preapproval is cancelled/paused.
     */
    @Transactional
    public void cancelSubscription(Long subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subscription not found: " + subscriptionId));
        subscription.setStatus(SubscriptionStatus.CANCELLED);
        subscriptionRepository.save(subscription);
        log.info("Cancelled subscription id={}", subscriptionId);
    }

    /**
     * Finds a subscription by MP preapproval id.
     * Used for webhook correlation when external_reference is the preapproval id.
     */
    @Transactional(readOnly = true)
    public Subscription findByMpPreapprovalId(String mpPreapprovalId) {
        return subscriptionRepository.findByMpPreapprovalIdAndActivoTrue(mpPreapprovalId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Subscription not found for mpPreapprovalId: " + mpPreapprovalId));
    }
}
