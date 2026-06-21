package com.tiam.subscription.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MercadoPagoProperties.class, PlansProperties.class})
public class MercadoPagoConfig {
    // Properties are bound and exposed as beans via @EnableConfigurationProperties.
    // SDK initialization happens lazily in MercadoPagoService when an operation is requested,
    // so the app boots fine without MP_ACCESS_TOKEN configured.
}
