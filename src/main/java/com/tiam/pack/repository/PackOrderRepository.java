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
}
