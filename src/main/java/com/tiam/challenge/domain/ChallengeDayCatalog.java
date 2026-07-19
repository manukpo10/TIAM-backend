package com.tiam.challenge.domain;

import java.util.List;
import java.util.Map;

/**
 * Day → (type, area) catalog for the 30-day challenge, mirrored from
 * TIAM-frontend/src/lib/challengeContent.ts's {@code DAYS_CONTENT} — the
 * source of truth for the actual content. Only the two fields the backend
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
 */
public final class ChallengeDayCatalog {

    /** Day info: type (game vs. static card) and cognitive area. */
    public record DayInfo(ChallengeDayType type, String area) {}

    private static final Map<Integer, DayInfo> DAYS = Map.ofEntries(
            Map.entry(1, new DayInfo(ChallengeDayType.GAME, "orientacion")),
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
            Map.entry(13, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(14, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(15, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(16, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(17, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(18, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(19, new DayInfo(ChallengeDayType.GAME, "memoria")),
            Map.entry(20, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(21, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(22, new DayInfo(ChallengeDayType.GAME, "calculo")),
            Map.entry(23, new DayInfo(ChallengeDayType.GAME, "agnosias")),
            Map.entry(24, new DayInfo(ChallengeDayType.GAME, "atencion")),
            Map.entry(25, new DayInfo(ChallengeDayType.GAME, "ejecutivas")),
            Map.entry(26, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(27, new DayInfo(ChallengeDayType.GAME, "agnosias")),
            Map.entry(28, new DayInfo(ChallengeDayType.GAME, "lenguaje")),
            Map.entry(29, new DayInfo(ChallengeDayType.GAME, "agnosias")),
            Map.entry(30, new DayInfo(ChallengeDayType.GAME, "calculo")));

    public static final int TOTAL_DAYS = DAYS.size();

    public static final long GAME_DAY_COUNT =
            DAYS.values().stream().filter(d -> d.type() == ChallengeDayType.GAME).count();

    /** All 8 cognitive areas, in a stable display order — used to build a zero-played breakdown. */
    public static final List<String> AREAS = List.of(
            "memoria", "atencion", "lenguaje", "praxias", "agnosias", "calculo", "orientacion", "ejecutivas");

    private ChallengeDayCatalog() {}

    public static DayInfo dayInfo(int day) {
        DayInfo info = DAYS.get(day);
        if (info == null) {
            throw new IllegalArgumentException("Unknown challenge day: " + day);
        }
        return info;
    }

    public static boolean isGameDay(int day) {
        return dayInfo(day).type() == ChallengeDayType.GAME;
    }
}
