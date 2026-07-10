package com.tiam.challenge.domain;

/**
 * Mirrors TIAM-frontend/src/lib/challengeContent.ts's {@code ChallengeDayType}
 * ('card' | 'game'). Only 25 of the 30 days are interactive games — the rest
 * are static reflection cards with no completion event, which matters for the
 * streak calculation in {@link com.tiam.challenge.service.ChallengeDayResultService}.
 */
public enum ChallengeDayType {
    GAME,
    CARD
}
