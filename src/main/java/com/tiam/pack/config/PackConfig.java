package com.tiam.pack.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PackProperties.class)
public class PackConfig {

    /** The time source of the pack services; tests substitute a movable one. */
    @Bean
    public Clock packClock() {
        return Clock.systemUTC();
    }
}
