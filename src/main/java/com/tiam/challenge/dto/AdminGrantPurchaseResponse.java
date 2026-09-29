package com.tiam.challenge.dto;

public record AdminGrantPurchaseResponse(
        String accessToken, String playLink, String buyerName, Integer challengeMonth) {
}
