package com.tiam.challenge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.whatsapp")
public class WhatsAppProperties {

    private String accessToken;
    private String phoneNumberId;
    private String verifyToken;
    private String appSecret;
    private String desafioPlayBaseUrl;
    private String salesPageUrl;
}
