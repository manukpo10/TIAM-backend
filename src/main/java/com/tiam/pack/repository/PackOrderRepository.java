package com.tiam.pack.repository;

import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.domain.PackOrder;
import com.tiam.pack.domain.PackOrderStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PackOrderRepository extends JpaRepository<PackOrder, Long> {

    Optional<PackOrder> findByAccessTokenAndActivoTrue(String accessToken);

    /** Row-locking read for payment-driven transitions: two near-simultaneous notifications queue up. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PackOrder> findWithLockById(Long id);

    /**
     * A checkout the same buyer started moments ago for exactly the same thing and that already has a
     * Mercado Pago link: a double click should go back to that link instead of creating a second order.
     * Newest first.
     */
    @Query("select o from PackOrder o where o.email = :email and o.edition = :edition "
            + "and o.withGames = :withGames and o.withExtra = :withExtra "
            + "and o.status = :status and o.activo = true and o.initPoint is not null "
            + "and o.createdAt >= :since order by o.createdAt desc")
    List<PackOrder> findReusable(
            @Param("email") String email,
            @Param("edition") PackEdition edition,
            @Param("withGames") boolean withGames,
            @Param("withExtra") boolean withExtra,
            @Param("status") PackOrderStatus status,
            @Param("since") Instant since);

    long countByEmailAndStatusAndCreatedAtAfter(String email, PackOrderStatus status, Instant since);

    /**
     * Orders whose payment notification may have been lost: stuck in {@code status}, created inside
     * [from, to], and that actually reached Mercado Pago (an order with no init_point never had a checkout,
     * so there is nothing to look up). Used by {@code PackOrderReconciliationService}.
     */
    @Query("select o from PackOrder o where o.status = :status and o.activo = true "
            + "and o.initPoint is not null and o.createdAt between :from and :to order by o.createdAt")
    List<PackOrder> findStalePending(
            @Param("status") PackOrderStatus status,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
