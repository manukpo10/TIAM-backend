package com.tiam.pack.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What a buyer sees at {@code GET /packs/orders/{token}}.
 *
 * <p>While the order is not paid only {@code status} and {@code edition} are filled (PENDING while the payment is
 * confirmed, FAILED when it was rejected, REVIEW when a payment was received but a person has to check it);
 * everything else is null / empty. The full body (names, files, the games link, the expiry, the total) appears
 * only for a PAID order whose access window is open.
 *
 * @param status    PENDING | PAID | FAILED | REVIEW
 * @param firstName the buyer's first name, for the greeting
 * @param edition   "familias" | "profesionales"
 * @param items     the products bought, each with its downloadable files
 * @param games     the bundled Desafío month, or null if the buyer did not buy it
 * @param expiresAt when the downloads stop working
 * @param total     what the buyer paid, in ARS (for the ad pixel's purchase value); null unless PAID
 */
public record PackOrderResponse(
        String status,
        String firstName,
        String edition,
        List<Item> items,
        Games games,
        Instant expiresAt,
        BigDecimal total) {

    /** A paid, open order. */
    public static PackOrderResponse paid(String firstName, String edition, List<Item> items, Games games,
            Instant expiresAt, BigDecimal total) {
        return new PackOrderResponse("PAID", firstName, edition, items, games, expiresAt, total);
    }

    /**
     * The minimal body for an order that is not (yet) downloadable. The edition is not sensitive and lets the
     * page send a buyer whose payment did not go through back to the landing they were looking at.
     */
    public static PackOrderResponse withoutFiles(String status, String edition) {
        return new PackOrderResponse(status, null, edition, List.of(), null, null, null);
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
