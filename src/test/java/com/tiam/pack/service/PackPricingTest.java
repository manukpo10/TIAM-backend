package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackEdition;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackPricingTest {

    PackProperties properties;
    PackPricing pricing;

    @BeforeEach
    void setUp() {
        properties = new PackProperties(); // defaults: 14990 / 10000 / 4990
        pricing = new PackPricing(properties);
    }

    @Test
    void packOnly_chargesJustThePackPrice() {
        PackPrice price = pricing.calculate(PackEdition.FAMILIAS, new PackExtras(false, false));

        assertThat(price.pack()).isEqualByComparingTo("14990");
        assertThat(price.games()).isEqualByComparingTo("0");
        assertThat(price.extra()).isEqualByComparingTo("0");
        assertThat(price.total()).isEqualByComparingTo("14990");
    }

    @Test
    void packPlusGames_addsTheGamesPrice() {
        PackPrice price = pricing.calculate(PackEdition.FAMILIAS, new PackExtras(true, false));

        assertThat(price.games()).isEqualByComparingTo("10000");
        assertThat(price.total()).isEqualByComparingTo("24990");
    }

    @Test
    void packPlusExtra_addsTheExtraPrice() {
        PackPrice price = pricing.calculate(PackEdition.PROFESIONALES, new PackExtras(false, true));

        assertThat(price.extra()).isEqualByComparingTo("4990");
        assertThat(price.total()).isEqualByComparingTo("19980");
    }

    @Test
    void packPlusBothExtras_addsBoth() {
        PackPrice price = pricing.calculate(PackEdition.FAMILIAS, new PackExtras(true, true));

        assertThat(price.total()).isEqualByComparingTo("29980");
    }

    @Test
    void amountsAlwaysCarryTwoDecimals_soTheyCompareCleanlyWithMercadoPagoAmounts() {
        PackPrice price = pricing.calculate(PackEdition.FAMILIAS, new PackExtras(true, true));

        assertThat(price.pack().scale()).isEqualTo(2);
        assertThat(price.games().scale()).isEqualTo(2);
        assertThat(price.extra().scale()).isEqualTo(2);
        assertThat(price.total().scale()).isEqualTo(2);
    }

    @Test
    void pricesComeFromConfiguration_notFromConstants() {
        properties.setPriceArs(new BigDecimal("100"));
        properties.setGamesPriceArs(new BigDecimal("20"));
        properties.setExtraPriceArs(new BigDecimal("3"));

        PackPrice price = pricing.calculate(PackEdition.FAMILIAS, new PackExtras(true, true));

        assertThat(price.total()).isEqualByComparingTo("123");
    }

    @Test
    void fractionalPrices_roundHalfUpToCents() {
        properties.setPriceArs(new BigDecimal("100.005"));

        PackPrice price = pricing.calculate(PackEdition.FAMILIAS, new PackExtras(false, false));

        assertThat(price.pack()).isEqualByComparingTo("100.01");
    }

    @Test
    void nonPositivePackPrice_isAMisconfigurationAndNeverSells() {
        properties.setPriceArs(BigDecimal.ZERO);

        assertThatThrownBy(() -> pricing.calculate(PackEdition.FAMILIAS, new PackExtras(false, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void negativeAddOnPrice_isAMisconfigurationAndNeverSells() {
        properties.setGamesPriceArs(new BigDecimal("-1"));

        assertThatThrownBy(() -> pricing.calculate(PackEdition.FAMILIAS, new PackExtras(true, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- extras parsing -------------------------------------------------------------

    @Test
    void parseExtras_nullOrEmpty_meansNoExtras() {
        assertThat(PackPricing.parseExtras(null)).isEqualTo(new PackExtras(false, false));
        assertThat(PackPricing.parseExtras(List.of())).isEqualTo(new PackExtras(false, false));
    }

    @Test
    void parseExtras_knownValues_areRecognisedIgnoringCaseAndSpaces() {
        assertThat(PackPricing.parseExtras(List.of("juegos"))).isEqualTo(new PackExtras(true, false));
        assertThat(PackPricing.parseExtras(List.of(" Cuadernillo "))).isEqualTo(new PackExtras(false, true));
        assertThat(PackPricing.parseExtras(List.of("JUEGOS", "cuadernillo"))).isEqualTo(new PackExtras(true, true));
    }

    @Test
    void parseExtras_repeatedValue_isNotChargedTwice() {
        assertThat(PackPricing.parseExtras(List.of("juegos", "juegos"))).isEqualTo(new PackExtras(true, false));
    }

    @Test
    void parseExtras_blankEntries_areIgnored() {
        assertThat(PackPricing.parseExtras(List.of("", "  "))).isEqualTo(new PackExtras(false, false));
    }

    @Test
    void parseExtras_unknownValue_isRejectedWithTheExtrasError() {
        assertThatThrownBy(() -> PackPricing.parseExtras(List.of("juegos", "regalo-gratis")))
                .isInstanceOfSatisfying(PackCheckoutException.class,
                        e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EXTRAS));
    }
}
