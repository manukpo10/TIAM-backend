package com.tiam.challenge.repository;

import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ChallengePurchaseRepository extends JpaRepository<ChallengePurchase, Long> {

    Optional<ChallengePurchase> findByAccessTokenAndActivoTrue(String accessToken);

    List<ChallengePurchase> findByPhoneAndActivoTrue(String phone);

    /**
     * Row-locking read for webhook-driven status transitions — closes the
     * read-then-write race between two near-simultaneous webhook deliveries
     * for the same purchase (e.g. a retried notification).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChallengePurchase> findWithLockById(Long id);

    /**
     * Used by {@link com.tiam.challenge.service.ChallengePurchaseReconciliationService}
     * to find purchases whose webhook notification may have been lost: stuck in the
     * given status, created within the [from, to] window (grace-period cutoff as the
     * upper bound, lookback cutoff as the lower bound).
     */
    List<ChallengePurchase> findByStatusAndActivoTrueAndCreatedAtBetween(
            ChallengePurchaseStatus status, Instant from, Instant to);
}
