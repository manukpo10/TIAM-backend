package com.tiam.pack.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PackProperties.class)
public class PackConfig {
    // Properties are bound and exposed as a bean via @EnableConfigurationProperties.
}
