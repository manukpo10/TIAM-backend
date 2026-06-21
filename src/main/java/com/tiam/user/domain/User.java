package com.tiam.user.domain;

import com.tiam.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = true)
    private String specialty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.PROFESSIONAL;

    // Mapped by the Subscription entity — not the owner side
    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    private com.tiam.subscription.domain.Subscription subscription;
}
