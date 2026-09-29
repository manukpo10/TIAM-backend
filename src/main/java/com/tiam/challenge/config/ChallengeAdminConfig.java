package com.tiam.challenge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChallengeAdminProperties.class)
public class ChallengeAdminConfig {
    // Properties are bound and exposed as a bean via @EnableConfigurationProperties.
}
