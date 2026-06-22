package com.tiam.lead.domain;

import com.tiam.common.audit.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A lead captured from the public "recursos gratuitos" lead magnet: a professional
 * who left their email to download the free sample worksheets.
 */
@Entity
@Table(name = "leads")
@Getter
@Setter
public class Lead extends BaseEntity {

    @Column(nullable = false)
    private String email;

    private String name;

    @Column(nullable = false)
    private String source = "recursos";

    @Column(nullable = false)
    private boolean consent = false;
}
