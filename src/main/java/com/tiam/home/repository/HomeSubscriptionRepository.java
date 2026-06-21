package com.tiam.home.repository;

import com.tiam.home.domain.HomeSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface HomeSubscriptionRepository extends JpaRepository<HomeSubscription, Long> {
    Optional<HomeSubscription> findByPatientId(Long patientId);
}
