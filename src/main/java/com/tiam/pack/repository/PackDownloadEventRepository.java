package com.tiam.pack.repository;

import com.tiam.pack.domain.PackDownloadEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackDownloadEventRepository extends JpaRepository<PackDownloadEvent, Long> {
}
