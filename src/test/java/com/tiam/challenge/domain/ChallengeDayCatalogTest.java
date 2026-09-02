package com.tiam.challenge.domain;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the hand-transcribed day -> area data for both challenge months. Month 2's
 * table is pure literal data with no computation behind it, so an exhaustive,
 * day-by-day assertion is the only thing that would catch a transcription slip
 * (e.g. two adjacent areas swapped) — a spot-check would silently miss it.
 */
class ChallengeDayCatalogTest {

    // --- month 1: unchanged regression coverage ---------------------------------

    @Test
    void dayInfo_month1_matchesOriginalCatalog() {
        assertThat(ChallengeDayCatalog.dayInfo(1, 1).area()).isEqualTo("lenguaje");
        assertThat(ChallengeDayCatalog.dayInfo(1, 4).area()).isEqualTo("atencion");
        assertThat(ChallengeDayCatalog.dayInfo(1, 9).area()).isEqualTo("praxias");
        assertThat(ChallengeDayCatalog.dayInfo(1, 13).area()).isEqualTo("orientacion");
        // día 22 was "lenguaje" (¿Qué oficio es?) until it was replaced by an
        // "ejecutivas" game (OficioIdeal) — this catalog wasn't updated at the
        // time, which silently pushed a played day into the wrong area's count
        // on the progress panel (a real bug caught live, not hypothetical).
        assertThat(ChallengeDayCatalog.dayInfo(1, 22).area()).isEqualTo("ejecutivas");
        assertThat(ChallengeDayCatalog.dayInfo(1, 30).area()).isEqualTo("memoria");
    }

    @Test
    void dayInfo_month1_allDaysAreGameType() {
        for (int day = 1; day <= 30; day++) {
            assertThat(ChallengeDayCatalog.dayInfo(1, day).type()).isEqualTo(ChallengeDayType.GAME);
        }
    }

    // --- month 2: exhaustive literal data check ---------------------------------

    @Test
    void dayInfo_month2_matchesFinalizedContentPlanForEveryDay() {
        Map<Integer, String> expectedAreaByDay = Map.ofEntries(
                Map.entry(1, "lenguaje"), Map.entry(2, "memoria"), Map.entry(3, "atencion"),
                Map.entry(4, "calculo"), Map.entry(5, "praxias"), Map.entry(6, "ejecutivas"),
                Map.entry(7, "lenguaje"), Map.entry(8, "memoria"), Map.entry(9, "atencion"),
                Map.entry(10, "ejecutivas"), Map.entry(11, "lenguaje"), Map.entry(12, "praxias"),
                Map.entry(13, "orientacion"), Map.entry(14, "lenguaje"), Map.entry(15, "ejecutivas"),
                Map.entry(16, "memoria"), Map.entry(17, "lenguaje"), Map.entry(18, "atencion"),
                Map.entry(19, "lenguaje"), Map.entry(20, "praxias"), Map.entry(21, "ejecutivas"),
                Map.entry(22, "calculo"), Map.entry(23, "memoria"), Map.entry(24, "atencion"),
                Map.entry(25, "lenguaje"), Map.entry(26, "ejecutivas"), Map.entry(27, "orientacion"),
                Map.entry(28, "atencion"), Map.entry(29, "calculo"), Map.entry(30, "calculo"));

        expectedAreaByDay.forEach((day, expectedArea) ->
                assertThat(ChallengeDayCatalog.dayInfo(2, day).area())
                        .as("day %d", day)
                        .isEqualTo(expectedArea));
    }

    @Test
    void dayInfo_month2_allDaysAreGameType() {
        for (int day = 1; day <= 30; day++) {
            assertThat(ChallengeDayCatalog.dayInfo(2, day).type()).isEqualTo(ChallengeDayType.GAME);
        }
    }

    @Test
    void dayInfo_month2DivergesFromMonth1_onDayFour() {
        // The concrete example the service-level tests build on: día 4 is "atencion"
        // in month 1 but "calculo" in month 2 — proves the two catalogs are genuinely
        // independent tables, not the same map reused under a new key.
        assertThat(ChallengeDayCatalog.dayInfo(1, 4).area()).isEqualTo("atencion");
        assertThat(ChallengeDayCatalog.dayInfo(2, 4).area()).isEqualTo("calculo");
    }

    // --- month 3: exhaustive literal data check ---------------------------------

    @Test
    void dayInfo_month3_matchesFinalizedContentPlanForEveryDay() {
        Map<Integer, String> expectedAreaByDay = Map.ofEntries(
                Map.entry(1, "lenguaje"), Map.entry(2, "memoria"), Map.entry(3, "calculo"),
                Map.entry(4, "praxias"), Map.entry(5, "orientacion"), Map.entry(6, "atencion"),
                Map.entry(7, "ejecutivas"), Map.entry(8, "lenguaje"), Map.entry(9, "memoria"),
                Map.entry(10, "atencion"), Map.entry(11, "calculo"), Map.entry(12, "praxias"),
                Map.entry(13, "orientacion"), Map.entry(14, "lenguaje"), Map.entry(15, "ejecutivas"),
                Map.entry(16, "lenguaje"),
                // día 17 was "memoria" (Fluencia con recuerdo, a study-then-recall
                // exercise) until it was redesigned into a pure-selection exercise
                // with nothing left to remember (Fluencia con reglas) — the frontend
                // catalog moved it to "atencion" but this backend mirror wasn't
                // updated at the same time, which silently inflated "memoria" past
                // 100% on the progress panel (día 22 in month 1 is the same bug,
                // see that test above — this is the second real occurrence).
                Map.entry(17, "atencion"),
                Map.entry(18, "lenguaje"), Map.entry(19, "calculo"), Map.entry(20, "praxias"),
                Map.entry(21, "atencion"), Map.entry(22, "agnosias"), Map.entry(23, "lenguaje"),
                Map.entry(24, "memoria"), Map.entry(25, "calculo"), Map.entry(26, "praxias"),
                Map.entry(27, "atencion"), Map.entry(28, "orientacion"), Map.entry(29, "ejecutivas"),
                Map.entry(30, "calculo"));

        expectedAreaByDay.forEach((day, expectedArea) ->
                assertThat(ChallengeDayCatalog.dayInfo(3, day).area())
                        .as("day %d", day)
                        .isEqualTo(expectedArea));
    }

    // --- error handling ----------------------------------------------------------

    @Test
    void dayInfo_unknownDay_throwsForBothMonths() {
        assertThatThrownBy(() -> ChallengeDayCatalog.dayInfo(1, 31))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChallengeDayCatalog.dayInfo(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dayInfo_unknownMonth_throws() {
        // Month 3 used to be unsupported when this test was written; it's a real
        // catalog now (see DAYS_MONTH_3), so asserting against it here would be
        // asserting the wrong thing — month 4 is the genuinely unknown one today.
        assertThatThrownBy(() -> ChallengeDayCatalog.dayInfo(4, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalDays_isThirtyForEveryMonth() {
        assertThat(ChallengeDayCatalog.TOTAL_DAYS).isEqualTo(30);
    }

    @Test
    void gameDayCount_isThirtyForMonthsOneAndTwo_butMonth3HasTwoCardDays() {
        assertThat(ChallengeDayCatalog.gameDayCount(1)).isEqualTo(30);
        assertThat(ChallengeDayCatalog.gameDayCount(2)).isEqualTo(30);
        // días 14 and 28 are CARD days (lápiz-y-papel, no completion event) —
        // see their entries in DAYS_MONTH_3 and challengeContent.ts.
        assertThat(ChallengeDayCatalog.gameDayCount(3)).isEqualTo(28);
        assertThat(ChallengeDayCatalog.dayInfo(3, 14).type()).isEqualTo(ChallengeDayType.CARD);
        assertThat(ChallengeDayCatalog.dayInfo(3, 28).type()).isEqualTo(ChallengeDayType.CARD);
    }
}
