package com.tiam.home.service;

import com.tiam.home.domain.HomeSubscription;
import com.tiam.home.domain.HomeSubscriptionStatus;
import com.tiam.home.repository.HomeSubscriptionRepository;
import com.tiam.patient.domain.Patient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class HomeSubscriptionService {

    private final HomeSubscriptionRepository homeSubscriptionRepository;

    @Transactional(readOnly = true)
    public boolean isActive(Long patientId) {
        return homeSubscriptionRepository.findByPatientId(patientId)
                .map(hs -> hs.getStatus() == HomeSubscriptionStatus.ACTIVE)
                .orElse(false);
    }

    @Transactional
    public void activate(Patient patient) {
        // TODO: when MP for patient is enabled, create a $12.000/mo preapproval like the
        // professional checkout and only activate on webhook.
        HomeSubscription sub = homeSubscriptionRepository.findByPatientId(patient.getId())
                .orElseGet(() -> {
                    HomeSubscription newSub = new HomeSubscription();
                    newSub.setPatient(patient);
                    newSub.setStatus(HomeSubscriptionStatus.INACTIVE);
                    return newSub;
                });
        sub.setStatus(HomeSubscriptionStatus.ACTIVE);
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        homeSubscriptionRepository.save(sub);
    }
}
