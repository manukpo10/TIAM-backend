package com.tiam.challenge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChallengeEmailRetryProperties.class)
public class ChallengeEmailRetryConfig {
    // Properties are bound and exposed as a bean via @EnableConfigurationProperties.
}
