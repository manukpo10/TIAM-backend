package com.tiam.challenge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChallengeReconciliationProperties.class)
public class ChallengeReconciliationConfig {
    // Properties are bound and exposed as a bean via @EnableConfigurationProperties.
}
