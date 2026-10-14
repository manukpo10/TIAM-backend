package com.tiam.pack.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** One signed download URL issued to an order: audit trail and the base of the anti-sharing caps. */
@Entity
@Table(name = "pack_download_events")
@Getter
@Setter
public class PackDownloadEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** The catalog file code ("pack-completo-a4"), never the storage key. */
    @Column(name = "file_key", nullable = false, length = 100)
    private String fileKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
