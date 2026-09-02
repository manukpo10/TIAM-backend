package com.tiam.challenge.domain;

import java.util.List;
import java.util.Map;

/**
 * (Month, day) → (type, area) catalog for the 30-day challenge, mirrored from
 * TIAM-frontend/src/lib/challengeContent.ts's per-month {@code DAYS_CONTENT} —
 * the source of truth for the actual content. Only the two fields the backend
 * needs (to gate which days are playable and to derive the cognitive area
 * server-side, never trusting the client) are duplicated here; titles,
 * instructions and copy stay frontend-only on purpose.
 *
 * This is the same kind of small intentional duplication already tolerated
 * in this codebase — see {@code ChallengePurchaseService.TOTAL_DAYS}, which
 * duplicates the frontend's {@code CHALLENGE_TOTAL_DAYS}.
 *
 * {@code area} values match the frontend's {@code ChallengeArea} union
 * exactly: memoria, atencion, lenguaje, praxias, agnosias, calculo,
 * orientacion, ejecutivas.
 *
 * <p>Each month (see {@link com.tiam.challenge.domain.ChallengePurchase#getChallengeMonth()})
 * is a completely independent one-time-purchase 30-day catalog with its own
 * day→area assignments, all sharing the same 30-day total. {@link #TOTAL_DAYS}
 * stays global (every month is 30 days) but the GAME/CARD mix is NOT
 * guaranteed identical across months — month 3's día 14 was the catalog's
 * first CARD day (a lápiz-y-papel exercise with no completion event), and
 * día 28 later joined it, so "how many game days" is now genuinely
 * month-specific. Use
 * {@link #gameDayCount(int)}, never a single global count, anywhere that
 * threshold matters (badges, streaks).
 */
public final class ChallengeDayCatalog {

    /** Day info: type (game vs. static card) and cognitive area. */
    public record DayInfo(ChallengeDayType type, String area) {}

    private static final Map<Integer, DayInfo> DAYS_MONTH_1 = Map.ofEntries(
            Map.entry(1, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(2, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(3, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(4, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(5, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(6, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(7, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(8, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(9, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(10, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(11, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(12, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(13, new DayInfo(ChallengeDayType.GAME, "orientacion")),
            Map.entry(14, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(15, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(16, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(17, new DayInfo(ChallengeDayType.GAME, "agnosias")),
            Map.entry(18, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(19, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(20, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(21, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(22, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(23, new DayInfo(ChallengeDayType.GAME, "agnosias")),
            Map.entry(24, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(25, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(26, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(27, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(28, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(29, new DayInfo(ChallengeDayType.GAME, "agnosias")),
            Map.entry(30, new DayInfo(ChallengeDayType.GAME, "memoria")));

    /** "Mes 2" — independent catalog, same day count/type shape as month 1, different areas. */
    private static final Map<Integer, DayInfo> DAYS_MONTH_2 = Map.ofEntries(
            Map.entry(1, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(2, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(3, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(4, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(5, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(6, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(7, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(8, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(9, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(10, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(11, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(12, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(13, new DayInfo(ChallengeDayType.GAME, "orientacion")),
            Map.entry(14, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(15, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(16, new DayInfo(ChallengeDayType.GAME, "memoria")),
            // día 17 was "agnosias" (Es esta sombra) until it was replaced by a
            // "lenguaje" game (Letras revueltas), and día 30 was "agnosias"
            // (Figuras superpuestas +) until it was replaced by a "calculo" game
            // (Antes y después) — this catalog wasn't updated at the time,
            // which would have silently pushed a played day into the wrong
            // area's count on the progress panel (same bug class as día 22 of
            // month 1, see the comment on that assertion in
            // ChallengeDayCatalogTest).
            Map.entry(17, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(18, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(19, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(20, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(21, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(22, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(23, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(24, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(25, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(26, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(27, new DayInfo(ChallengeDayType.GAME, "orientacion")),
            Map.entry(28, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(29, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(30, new DayInfo(ChallengeDayType.GAME, "calculo")));

    /** "Mes 3" — independent catalog, same day count/type shape as months 1-2, different areas. */
    private static final Map<Integer, DayInfo> DAYS_MONTH_3 = Map.ofEntries(
            Map.entry(1, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(2, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(3, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(4, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(5, new DayInfo(ChallengeDayType.GAME, "orientacion")),
            Map.entry(6, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(7, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(8, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(9, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(10, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(11, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(12, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(13, new DayInfo(ChallengeDayType.GAME, "orientacion")),
            Map.entry(14, new DayInfo(ChallengeDayType.CARD, "lenguaje")),
            Map.entry(15, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(16, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(17, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(18, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(19, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(20, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(21, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(22, new DayInfo(ChallengeDayType.GAME, "agnosias")),
            Map.entry(23, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(24, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(25, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(26, new DayInfo(ChallengeDayType.GAME, "praxias")),
            Map.entry(27, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(28, new DayInfo(ChallengeDayType.CARD, "orientacion")),
            Map.entry(29, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(30, new DayInfo(ChallengeDayType.GAME, "calculo")));

    public static final int TOTAL_DAYS = DAYS_MONTH_1.size();

    /** How many of this month's 30 days are actually completable games — see class doc. */
    public static long gameDayCount(int challengeMonth) {
        return catalogFor(challengeMonth).values().stream().filter(d -> d.type() == ChallengeDayType.GAME).count();
    }

    /** All 8 cognitive areas, in a stable display order — used to build a zero-played breakdown. */
    public static final List<String> AREAS = List.of(
            "memoria", "atencion", "lenguaje", "praxias", "agnosias", "calculo", "orientacion", "ejecutivas");

    private ChallengeDayCatalog() {}

    public static DayInfo dayInfo(int challengeMonth, int day) {
        DayInfo info = catalogFor(challengeMonth).get(day);
        if (info == null) {
            throw new IllegalArgumentException(
                    "Unknown challenge day: " + day + " (month " + challengeMonth + ")");
        }
        return info;
    }

    public static boolean isGameDay(int challengeMonth, int day) {
        return dayInfo(challengeMonth, day).type() == ChallengeDayType.GAME;
    }

    private static Map<Integer, DayInfo> catalogFor(int challengeMonth) {
        return switch (challengeMonth) {
            case 1 -> DAYS_MONTH_1;
            case 2 -> DAYS_MONTH_2;
            case 3 -> DAYS_MONTH_3;
            default -> throw new IllegalArgumentException("Unknown challenge month: " + challengeMonth);
        };
    }
}
