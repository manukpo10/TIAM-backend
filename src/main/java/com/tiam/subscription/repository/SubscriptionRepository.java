package com.tiam.subscription.repository;

import com.tiam.subscription.domain.Subscription;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByUserIdAndActivoTrue(Long userId);

    Optional<Subscription> findByMpPreapprovalIdAndActivoTrue(String mpPreapprovalId);
}
