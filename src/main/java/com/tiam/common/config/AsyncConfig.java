package com.tiam.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables {@code @Async} application-wide (currently just the after-payment access email in
 * {@link com.tiam.challenge.service.ChallengePurchaseEmailService}). Uses Spring Boot's
 * auto-configured {@code applicationTaskExecutor}.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
