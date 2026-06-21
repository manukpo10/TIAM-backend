package com.tiam.home.domain;

import com.tiam.common.audit.BaseEntity;
import com.tiam.patient.domain.Patient;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "home_subscriptions")
@Getter
@Setter
public class HomeSubscription extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id", nullable = false, unique = true)
    private Patient patient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HomeSubscriptionStatus status;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "mp_preapproval_id")
    private String mpPreapprovalId;
}
