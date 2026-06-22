package com.tiam.lead.repository;

import com.tiam.lead.domain.Lead;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadRepository extends JpaRepository<Lead, Long> {
}
