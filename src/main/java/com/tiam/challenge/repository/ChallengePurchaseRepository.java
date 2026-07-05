package com.tiam.challenge.repository;

import com.tiam.challenge.domain.ChallengePurchase;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface ChallengePurchaseRepository extends JpaRepository<ChallengePurchase, Long> {

    Optional<ChallengePurchase> findByAccessTokenAndActivoTrue(String accessToken);

    /**
     * Row-locking read for webhook-driven status transitions — closes the
     * read-then-write race between two near-simultaneous webhook deliveries
     * for the same purchase (e.g. a retried notification).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ChallengePurchase> findWithLockById(Long id);
}
