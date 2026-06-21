package com.tiam.subscription.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.mercadopago")
public class MercadoPagoProperties {

    private String accessToken;
    private String webhookSecret;
}
