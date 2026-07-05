package com.tiam.subscription.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.challenge")
public class ChallengeProperties {

    /** Same URL used for success/failure/pending until dedicated result pages exist. */
    private String backUrl;
}
