package com.tiam.challenge.dto;

/**
 * One month the buyer owns, as the player needs it to switch between months under a single link: which month it
 * is, the token the progress calls of THAT month use (a month keeps its own progress), and how many days of it are
 * open today. The token is only ever handed to whoever already holds a link of the same buyer.
 */
public record ChallengeMonthAccess(int challengeMonth, String accessToken, int currentDay, int totalDays) {
}
