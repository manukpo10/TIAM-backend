package com.tiam.challenge.dto;

/**
 * {@code code} is a stable machine-readable identifier (FIRST_DAY, STREAK_3,
 * STREAK_7, HALFWAY, CHALLENGE_COMPLETE, PERFECT_DAY) — label/icon/copy for
 * each badge is a frontend concern (Fase 3), the same "backend derives, frontend
 * labels" split already used for {@code area}.
 */
public record ChallengeBadgeResponse(String code, boolean earned) {
}
