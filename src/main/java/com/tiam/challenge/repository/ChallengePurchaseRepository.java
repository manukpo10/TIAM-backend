package com.tiam.challenge.repository;

import com.tiam.challenge.domain.ChallengePurchase;
import com.tiam.challenge.domain.ChallengePurchaseStatus;
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

public interface ChallengePurchaseRepository extends JpaRepository<ChallengePurchase, Long> {

    Optional<ChallengePurchase> findByAccessTokenAndActivoTrue(String accessToken);

    /** Whether an active purchase with this status carries this email (case-insensitive). */
    boolean existsByEmailIgnoreCaseAndStatusAndActivoTrue(String email, ChallengePurchaseStatus status);

    List<ChallengePurchase> findByPhoneAndActivoTrue(String phone);

    /** The months bought from the player on top of a first purchase (see {@code parentPurchaseId}). */
    List<ChallengePurchase> findByParentPurchaseIdAndActivoTrue(Long parentPurchaseId);

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

    /**
     * Ids of Mercado Pago purchases whose access email still has to be delivered: in the
     * given status, with a buyer email, {@code email_sent_at} NULL, and paid inside
     * [{@code since}, {@code until}] (oldest first). Used by
     * {@link com.tiam.challenge.service.ChallengeAccessEmailRetryService}.
     *
     * <p>{@code mpPaymentId is not null} keeps manual admin grants out on purpose: they have
     * no Mercado Pago payment and are handed over by hand, so they never get an automatic
     * email (consistent with the after-payment path, which only fires from {@code markPaid}).
     */
    @Query("select p.id from ChallengePurchase p "
            + "where p.status = :status and p.activo = true "
            + "and p.emailSentAt is null "
            + "and p.email is not null and length(trim(p.email)) > 0 "
            + "and p.mpPaymentId is not null "
            + "and p.purchaseDate >= :since and p.purchaseDate <= :until "
            + "order by p.purchaseDate")
    List<Long> findIdsAwaitingAccessEmail(
            @Param("status") ChallengePurchaseStatus status,
            @Param("since") Instant since,
            @Param("until") Instant until);

    /**
     * Atomically records that the access email was delivered, only if it has not
     * been recorded yet. Returns the number of rows changed (1 = recorded, 0 =
     * already recorded or unknown id).
     *
     * <p>{@code @Transactional} lives here (not on a service) on purpose: a bulk
     * {@code @Modifying} query needs its own transaction, and the caller is the
     * non-transactional async email listener.
     */
    @Modifying
    @Transactional
    @Query("update ChallengePurchase p set p.emailSentAt = :sentAt, p.updatedAt = :sentAt "
            + "where p.id = :id and p.emailSentAt is null")
    int markEmailSent(@Param("id") Long id, @Param("sentAt") Instant sentAt);
}
