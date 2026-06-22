package com.tiam.lead.service;

import com.tiam.lead.domain.Lead;
import com.tiam.lead.dto.CreateLeadRequest;
import com.tiam.lead.dto.LeadResponse;
import com.tiam.lead.repository.LeadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LeadService {

    private final LeadRepository leadRepository;

    /** Captures a new lead. Email is normalised to lowercase. */
    @Transactional
    public void capture(CreateLeadRequest request) {
        Lead lead = new Lead();
        lead.setEmail(request.email().trim().toLowerCase());
        lead.setName(request.name() != null ? request.name().trim() : null);
        lead.setSource(request.source() != null && !request.source().isBlank()
            ? request.source().trim() : "recursos");
        lead.setConsent(request.consent());
        leadRepository.save(lead);
    }

    /** Lists all captured leads, newest first (admin only). */
    @Transactional(readOnly = true)
    public List<LeadResponse> findAll() {
        return leadRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
            .map(l -> new LeadResponse(
                l.getId(), l.getEmail(), l.getName(), l.getSource(), l.isConsent(), l.getCreatedAt()))
            .toList();
    }
}
