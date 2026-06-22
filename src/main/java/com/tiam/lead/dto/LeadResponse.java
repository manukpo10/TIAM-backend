package com.tiam.lead.dto;

import java.time.Instant;

/** Admin-facing view of a captured lead. */
public record LeadResponse(
    Long id,
    String email,
    String name,
    String source,
    boolean consent,
    Instant createdAt
) {}
