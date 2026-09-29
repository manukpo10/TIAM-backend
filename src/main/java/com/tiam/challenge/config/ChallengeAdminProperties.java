package com.tiam.challenge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.challenge")
public class ChallengeAdminProperties {

    /** Shared secret for POST /challenge/admin/grants — see ChallengePurchaseController. */
    private String adminGrantSecret;
}
