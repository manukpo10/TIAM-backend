package com.tiam.subscription.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "tiam.plans")
public class PlansProperties {

    private double professionalMonthlyAmount;
    private double professionalAnnualAmount;
    private String backUrlSuccess;
    private String backUrlFailure;
    private String backUrlPending;
    private String notificationUrl;
}
