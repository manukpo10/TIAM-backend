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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PackOrderRepository extends JpaRepository<PackOrder, Long> {

    Optional<PackOrder> findByAccessTokenAndActivoTrue(String accessToken);

    /** The order a pending replacement token (see PackOrder#getNextAccessToken) belongs to. */
    Optional<PackOrder> findByNextAccessTokenAndActivoTrue(String nextAccessToken);

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

    /** The buyer's paid orders, most recently paid first. Used when they ask for their access again. */
    List<PackOrder> findByEmailAndStatusAndActivoTrueOrderByPaidAtDesc(String email, PackOrderStatus status);

    /** Whether this address has bought (or had a purchase held / refunded): used to decide who gets a regret receipt. */
    boolean existsByEmailAndStatusInAndActivoTrue(String email, java.util.Collection<PackOrderStatus> statuses);

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

    /**
     * Ids of PAID orders whose access email still has to be delivered, paid inside [since, until], oldest
     * first. Used by {@code PackEmailRetryService}.
     */
    @Query("select o.id from PackOrder o where o.status = :status and o.activo = true "
            + "and o.emailSentAt is null and length(trim(o.email)) > 0 "
            + "and o.paidAt >= :since and o.paidAt <= :until order by o.paidAt")
    List<Long> findIdsAwaitingAccessEmail(
            @Param("status") PackOrderStatus status,
            @Param("since") Instant since,
            @Param("until") Instant until);

    /**
     * Atomically records that the access email was delivered, only if it has not been recorded yet.
     * Returns the rows changed (1 = recorded, 0 = already recorded or unknown id).
     *
     * <p>{@code @Transactional} lives here on purpose: a bulk {@code @Modifying} query needs its own
     * transaction and the caller is the non-transactional async email listener.
     */
    @Modifying
    @Transactional
    @Query("update PackOrder o set o.emailSentAt = :sentAt, o.updatedAt = :sentAt "
            + "where o.id = :id and o.emailSentAt is null")
    int markEmailSent(@Param("id") Long id, @Param("sentAt") Instant sentAt);
}
