package com.tiam.subscription.mapper;

import com.tiam.subscription.domain.Subscription;
import com.tiam.subscription.dto.SubscriptionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SubscriptionMapper {

    SubscriptionResponse toResponse(Subscription subscription);
}
