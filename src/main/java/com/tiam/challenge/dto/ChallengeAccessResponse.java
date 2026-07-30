package com.tiam.challenge.dto;

public record ChallengeAccessResponse(
        String buyerFirstName,
        int currentDay,
        int totalDays,
        // Which 30-day catalog this access token belongs to (1 or 2) — the frontend
        // has no other way to tell a month-2 purchase apart from month-1 before any
        // day is completed, so it needs this to pick the right local content/game
        // registry to render.
        int challengeMonth) {
}
