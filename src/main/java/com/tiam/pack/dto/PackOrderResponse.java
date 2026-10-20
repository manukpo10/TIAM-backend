package com.tiam.pack.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
 * @param edition   "familias" | "profesionales" | "cuadernos"
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

    /**
     * The bundled Desafío month.
     *
     * <p>Every field after {@code link} exists only for the Cuadernos offer (ONE month, no renewal) and is OMITTED from
     * the JSON when null, so the Pack's response is exactly what it always was.
     *
     * @param link                     the buyer's personal Desafío link, or null if it could not be produced (use WhatsApp)
     * @param monthsIncluded           how many Desafío months the purchase includes (1)
     * @param month                    which month it is ("Mes N del Desafío": the buyer's next unpaid one, not always the
     *                                 first); omitted when it could not be read, then say "1 mes del Desafío"
     * @param durationDays             how long the month runs (30)
     * @param startsAt                 when the 30 days start counting: "purchase" (the payment). They do NOT wait for a
     *                                 WhatsApp message: the buyer can start right away from the link, or by WhatsApp
     * @param nextMonthsPaidSeparately true: the following months are optional and paid on the Desafío's own page
     * @param nextMonthPriceArs        the normal price of one of those following months, in ARS
     */
    public record Games(
            String link,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer monthsIncluded,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer month,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer durationDays,
            @JsonInclude(JsonInclude.Include.NON_NULL) String startsAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean nextMonthsPaidSeparately,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer nextMonthPriceArs) {

        /** The Pack's add-on: just the link. */
        public Games(String link) {
            this(link, null, null, null, null, null, null);
        }

        /**
         * The Cuadernos offer: one Desafío month (number {@code month} when known), 30 days from the purchase; the next
         * ones are optional and paid apart at the normal price.
         */
        public static Games includedMonth(String link, Integer month, BigDecimal normalMonthPriceArs) {
            return new Games(link, 1, month, 30, "purchase", true, normalMonthPriceArs.intValueExact());
        }
    }
}
