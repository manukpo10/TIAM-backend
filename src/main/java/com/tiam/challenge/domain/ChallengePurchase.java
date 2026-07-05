package com.tiam.challenge.domain;

import com.tiam.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "challenge_purchases")
@Getter
@Setter
public class ChallengePurchase extends BaseEntity {

    @Column(name = "buyer_name", nullable = false)
    private String buyerName;

    @Column(nullable = false)
    private String phone;

    @Column
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChallengePurchaseStatus status;

    @Column(name = "access_token", nullable = false, unique = true)
    private String accessToken;

    @Column(name = "purchase_date")
    private Instant purchaseDate;

    @Column(name = "mp_payment_id")
    private String mpPaymentId;
}
