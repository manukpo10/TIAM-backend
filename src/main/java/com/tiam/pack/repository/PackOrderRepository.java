package com.tiam.pack.repository;

import com.tiam.pack.domain.PackOrder;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PackOrderRepository extends JpaRepository<PackOrder, Long> {

    Optional<PackOrder> findByAccessTokenAndActivoTrue(String accessToken);

    /** Row-locking read for payment-driven transitions: two near-simultaneous notifications queue up. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PackOrder> findWithLockById(Long id);
}
