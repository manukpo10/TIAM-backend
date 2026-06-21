package com.tiam.subscription.mapper;

import com.tiam.subscription.domain.Subscription;
import com.tiam.subscription.domain.SubscriptionStatus;
import com.tiam.subscription.dto.SubscriptionResponse;
import java.time.Instant;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-21T04:32:04-0300",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.7 (Oracle Corporation)"
)
@Component
public class SubscriptionMapperImpl implements SubscriptionMapper {

    @Override
    public SubscriptionResponse toResponse(Subscription subscription) {
        if ( subscription == null ) {
            return null;
        }

        Long id = null;
        SubscriptionStatus status = null;
        Instant trialEndsAt = null;
        Instant currentPeriodEnd = null;

        id = subscription.getId();
        status = subscription.getStatus();
        trialEndsAt = subscription.getTrialEndsAt();
        currentPeriodEnd = subscription.getCurrentPeriodEnd();

        SubscriptionResponse subscriptionResponse = new SubscriptionResponse( id, status, trialEndsAt, currentPeriodEnd );

        return subscriptionResponse;
    }
}
