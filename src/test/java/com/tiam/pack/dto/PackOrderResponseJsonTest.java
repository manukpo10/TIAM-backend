package com.tiam.pack.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The wire shape of the downloads page data: additive for the Cuadernos, byte-identical for the Pack. */
class PackOrderResponseJsonTest {

    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void packGames_serialiseExactlyAsBefore_onlyTheLink() throws Exception {
        PackOrderResponse response = PackOrderResponse.paid("Ana", "familias", List.of(),
                new PackOrderResponse.Games("https://tiamdigital.com/desafio/tok"), Instant.parse("2027-01-01T00:00:00Z"),
                new BigDecimal("24990.00"));

        JsonNode games = mapper.readTree(mapper.writeValueAsString(response)).get("games");

        assertThat(games.fieldNames()).toIterable().containsExactly("link");
        assertThat(games.get("link").asText()).isEqualTo("https://tiamdigital.com/desafio/tok");
    }

    @Test
    void packGamesWithoutLink_stillCarryALinkFieldSetToNull() throws Exception {
        PackOrderResponse response = PackOrderResponse.paid("Ana", "familias", List.of(), new PackOrderResponse.Games((String) null),
                Instant.parse("2027-01-01T00:00:00Z"), new BigDecimal("24990.00"));

        JsonNode games = mapper.readTree(mapper.writeValueAsString(response)).get("games");

        assertThat(games.has("link")).isTrue();
        assertThat(games.get("link").isNull()).isTrue();
        assertThat(games.size()).isEqualTo(1);
    }

    @Test
    void cuadernosGames_addTheFacts_besideTheLink_andNeverClaimThatWhatsAppStartsTheClock() throws Exception {
        PackOrderResponse response = PackOrderResponse.paid("Ana", "cuadernos", List.of(),
                PackOrderResponse.Games.includedMonth("https://tiamdigital.com/desafio/tok", 2, new BigDecimal("20000")),
                Instant.parse("2027-01-01T00:00:00Z"), new BigDecimal("35000.00"));

        JsonNode games = mapper.readTree(mapper.writeValueAsString(response)).get("games");

        assertThat(games.get("link").asText()).isEqualTo("https://tiamdigital.com/desafio/tok");
        assertThat(games.get("monthsIncluded").asInt()).isEqualTo(1);
        assertThat(games.get("month").asInt()).isEqualTo(2);
        assertThat(games.get("durationDays").asInt()).isEqualTo(30);
        assertThat(games.get("startsAt").asText()).isEqualTo("purchase");
        assertThat(games.get("nextMonthsPaidSeparately").asBoolean()).isTrue();
        assertThat(games.get("nextMonthPriceArs").asInt()).isEqualTo(20000);
        assertThat(games.has("startsVia")).as("the old field promised a WhatsApp start the Desafío does not have").isFalse();
    }

    @Test
    void cuadernosGamesWithoutLinkOrMonth_keepALinkFieldSetToNull_andOmitTheUnknownMonth() throws Exception {
        PackOrderResponse response = PackOrderResponse.paid("Ana", "cuadernos", List.of(),
                PackOrderResponse.Games.includedMonth(null, null, new BigDecimal("20000")),
                Instant.parse("2027-01-01T00:00:00Z"), new BigDecimal("35000.00"));

        JsonNode games = mapper.readTree(mapper.writeValueAsString(response)).get("games");

        assertThat(games.has("link")).isTrue();
        assertThat(games.get("link").isNull()).isTrue();
        assertThat(games.get("monthsIncluded").asInt()).isEqualTo(1);
        assertThat(games.has("month")).isFalse();
    }
}
