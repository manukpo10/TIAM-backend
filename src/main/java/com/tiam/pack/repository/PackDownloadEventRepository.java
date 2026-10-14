package com.tiam.pack.repository;

import com.tiam.pack.domain.PackDownloadEvent;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackDownloadEventRepository extends JpaRepository<PackDownloadEvent, Long> {

    long countByOrderIdAndCreatedAtAfter(Long orderId, Instant since);

    long countByOrderId(Long orderId);
}
