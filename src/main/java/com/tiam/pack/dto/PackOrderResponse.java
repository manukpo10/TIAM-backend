package com.tiam.pack.dto;

import java.time.Instant;
import java.util.List;

/**
 * What a buyer sees at {@code GET /packs/orders/{token}}.
 *
 * <p>While the order is not paid only {@code status} is filled (PENDING while the payment is confirmed,
 * FAILED when it was rejected); everything else is null / empty. The full body (names, files, the games
 * link, the expiry) appears only for a PAID order whose access window is open.
 *
 * @param status    PENDING | PAID | FAILED
 * @param firstName the buyer's first name, for the greeting
 * @param edition   "familias" | "profesionales"
 * @param items     the products bought, each with its downloadable files
 * @param games     the bundled Desafío month, or null if the buyer did not buy it
 * @param expiresAt when the downloads stop working
 */
public record PackOrderResponse(
        String status,
        String firstName,
        String edition,
        List<Item> items,
        Games games,
        Instant expiresAt) {

    /** A paid, open order. */
    public static PackOrderResponse paid(String firstName, String edition, List<Item> items, Games games, Instant expiresAt) {
        return new PackOrderResponse("PAID", firstName, edition, items, games, expiresAt);
    }

    /** The minimal body for an order that is not (yet) downloadable. */
    public static PackOrderResponse withoutFiles(String status) {
        return new PackOrderResponse(status, null, null, List.of(), null, null);
    }

    /** @param code "PACK" or "EXTRA" */
    public record Item(String code, String title, List<FileInfo> files) {
    }

    /**
     * @param key    the catalog file code to put in the link-issuing URL (NOT a storage key)
     * @param paper  "A4" | "Carta"
     * @param sizeMb approximate size
     */
    public record FileInfo(String key, String label, String paper, int sizeMb) {
    }

    /** @param link the buyer's personal Desafío link, or null if it could not be produced (use WhatsApp) */
    public record Games(String link) {
    }
}
