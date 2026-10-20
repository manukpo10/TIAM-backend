package com.tiam.pack.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tiam.pack.config.PackProperties;
import com.tiam.pack.domain.PackEdition;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Prices of the «Cuadernos TIAM» product: decided on the server, never by the browser. */
class CuadernosPricingTest {

    PackProperties properties;
    PackPricing pricing;

    @BeforeEach
    void setUp() {
        properties = new PackProperties(); // defaults: cuadernos 20000, games 10000
        pricing = new PackPricing(properties);
    }

    @Test
    void cuadernosOnly_chargesJustTheCuadernosPrice() {
        PackPrice price = pricing.calculate(PackEdition.CUADERNOS, new PackExtras(false, false));

        assertThat(price.pack()).isEqualByComparingTo("20000");
        assertThat(price.games()).isEqualByComparingTo("0");
        assertThat(price.extra()).isEqualByComparingTo("0");
        assertThat(price.total()).isEqualByComparingTo("20000");
    }

    @Test
    void withTheGamesMonth_addsTheSameGamesPriceThePackUses() {
        properties.setGamesPriceArs(new BigDecimal("12500"));

        PackPrice price = pricing.calculate(PackEdition.CUADERNOS, new PackExtras(true, false));

        assertThat(price.games()).isEqualByComparingTo("12500");
        assertThat(price.total()).isEqualByComparingTo("32500");
    }

    @Test
    void defaultGamesPrice_isTenThousand() {
        PackPrice price = pricing.calculate(PackEdition.CUADERNOS, new PackExtras(true, false));

        assertThat(price.games()).isEqualByComparingTo("10000");
        assertThat(price.total()).isEqualByComparingTo("30000");
    }

    @Test
    void thePriceComesFromItsOwnSetting_notFromThePacksPrice() {
        properties.setPriceArs(new BigDecimal("1")); // the Pack price must be irrelevant
        properties.getCuadernos().setPriceArs(new BigDecimal("19990"));

        assertThat(pricing.calculate(PackEdition.CUADERNOS, new PackExtras(false, false)).total())
                .isEqualByComparingTo("19990");
    }

    @Test
    void thePackKeepsItsOwnPrice_whateverTheCuadernosCost() {
        properties.getCuadernos().setPriceArs(new BigDecimal("55555"));

        for (PackEdition edition : PackEdition.packEditions()) {
            assertThat(pricing.calculate(edition, new PackExtras(false, false)).total()).isEqualByComparingTo("14990");
            assertThat(pricing.calculate(edition, new PackExtras(true, true)).total()).isEqualByComparingTo("29980");
        }
    }

    @Test
    void theExtraBooklet_doesNotApplyToTheCuadernos_andIsRefusedWithTheExtrasError() {
        assertThatThrownBy(() -> pricing.calculate(PackEdition.CUADERNOS, new PackExtras(false, true)))
                .isInstanceOfSatisfying(PackCheckoutException.class,
                        e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EXTRAS));
        assertThatThrownBy(() -> pricing.calculate(PackEdition.CUADERNOS, new PackExtras(true, true)))
                .isInstanceOfSatisfying(PackCheckoutException.class,
                        e -> assertThat(e.error()).isEqualTo(PackCheckoutError.EXTRAS));
    }

    @Test
    void amountsAlwaysCarryTwoDecimals() {
        PackPrice price = pricing.calculate(PackEdition.CUADERNOS, new PackExtras(true, false));

        assertThat(price.pack().scale()).isEqualTo(2);
        assertThat(price.games().scale()).isEqualTo(2);
        assertThat(price.extra().scale()).isEqualTo(2);
        assertThat(price.total().scale()).isEqualTo(2);
    }

    @Test
    void fractionalPrices_roundHalfUpToCents() {
        properties.getCuadernos().setPriceArs(new BigDecimal("100.005"));

        assertThat(pricing.calculate(PackEdition.CUADERNOS, new PackExtras(false, false)).pack())
                .isEqualByComparingTo("100.01");
    }

    @Test
    void aNonPositiveCuadernosPrice_isAMisconfigurationAndNeverSells() {
        properties.getCuadernos().setPriceArs(BigDecimal.ZERO);

        assertThatThrownBy(() -> pricing.calculate(PackEdition.CUADERNOS, new PackExtras(false, false)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CUADERNOS_PRICE_ARS");
    }

    @Test
    void aMisconfiguredCuadernosPrice_doesNotBreakThePack() {
        properties.getCuadernos().setPriceArs(BigDecimal.ZERO);

        assertThat(pricing.calculate(PackEdition.FAMILIAS, new PackExtras(false, false)).total())
                .isEqualByComparingTo("14990");
    }
}
