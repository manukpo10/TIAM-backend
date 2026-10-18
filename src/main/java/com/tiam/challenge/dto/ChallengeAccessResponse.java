package com.tiam.challenge.dto;

import java.util.List;

public record ChallengeAccessResponse(
        String buyerFirstName,
        int currentDay,
        int totalDays,
        // Which 30-day catalog this access token belongs to (1 = original, 2+ = the
        // later months, including one granted ahead of its launch) — the frontend
        // has no other way to tell a month-2 purchase apart from month-1 before any
        // day is completed, so it needs this to pick the right local content/game
        // registry to render.
        int challengeMonth,
        // Every month the buyer owns under this one link (the first purchase plus the
        // months bought from the player), lowest month first. The fields above describe
        // the token that was opened; this lists them all so the player can switch month.
        List<ChallengeMonthAccess> months,
        // The month the player may offer next (null = nothing to offer: the last month on
        // sale, or the buyer already owns it) and its price in ARS (what Mercado Pago charges).
        Integer nextMonth,
        Integer nextMonthPriceArs) {
}
