package com.tiam.challenge.dto;

/**
 * Body of "Renovar y desbloquear el mes siguiente". The buyer types nothing: the name, WhatsApp number and email come
 * from the purchase on file (the bot finds the purchase by phone, so the number on file is the safest one).
 *
 * @param challengeMonth the month the player is offering. Optional: when sent, the server refuses to charge any other
 *                       month, so a stale page (the back button after paying) can never buy the one after it by surprise.
 */
public record NextMonthRequest(Integer challengeMonth) {
}
