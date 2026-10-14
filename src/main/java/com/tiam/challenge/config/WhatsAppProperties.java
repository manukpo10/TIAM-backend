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

    /**
     * TIAM's public WhatsApp business number, international format without "+"
     * (e.g. 5492214817297), used to build the wa.me link in the access email. It is
     * the buyer-facing number, not the Cloud API {@code phoneNumberId}.
     */
    private String businessNumber;
}
