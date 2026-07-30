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
                Map.entry(16, "memoria"), Map.entry(17, "agnosias"), Map.entry(18, "atencion"),
                Map.entry(19, "lenguaje"), Map.entry(20, "praxias"), Map.entry(21, "ejecutivas"),
                Map.entry(22, "calculo"), Map.entry(23, "memoria"), Map.entry(24, "atencion"),
                Map.entry(25, "lenguaje"), Map.entry(26, "ejecutivas"), Map.entry(27, "orientacion"),
                Map.entry(28, "atencion"), Map.entry(29, "calculo"), Map.entry(30, "agnosias"));

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
        assertThatThrownBy(() -> ChallengeDayCatalog.dayInfo(3, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalDaysAndGameDayCount_areThirtyForTheSharedShape() {
        assertThat(ChallengeDayCatalog.TOTAL_DAYS).isEqualTo(30);
        assertThat(ChallengeDayCatalog.GAME_DAY_COUNT).isEqualTo(30);
    }
}
