package com.tiam.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} methods application-wide (currently just
 * {@link com.tiam.challenge.service.ChallengePurchaseReconciliationService}).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
