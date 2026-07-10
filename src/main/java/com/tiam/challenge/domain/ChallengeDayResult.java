package com.tiam.challenge.domain;

import com.tiam.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One recorded completion of a challenge day (stars earned, mistakes made).
 * {@code area} is always derived server-side from {@link ChallengeDayCatalog},
 * never trusted from the client. {@code playedAt} is always set server-side
 * with {@code Instant.now()}. Upsert-keyed by (challengePurchase, day) — see
 * the unique index in V9__challenge_day_results.sql.
 */
@Entity
@Table(name = "challenge_day_results")
@Getter
@Setter
public class ChallengeDayResult extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "challenge_purchase_id", nullable = false)
    private ChallengePurchase challengePurchase;

    @Column(nullable = false)
    private Integer day;

    @Column(nullable = false)
    private String area;

    @Column(nullable = false)
    private Integer mistakes;

    @Column(name = "total_attempts", nullable = false)
    private Integer totalAttempts;

    @Column(nullable = false)
    private Integer stars;

    @Column(name = "played_at", nullable = false)
    private Instant playedAt;
}
