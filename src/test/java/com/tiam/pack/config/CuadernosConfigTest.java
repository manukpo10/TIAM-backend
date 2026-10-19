package com.tiam.pack.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** The two settings of the «Cuadernos TIAM» product: its own sales switch and its own server-side price. */
class CuadernosConfigTest {

    private static Properties yaml;

    @BeforeAll
    static void load() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        yaml = factory.getObject();
    }

    @Test
    void sales_areOffUnlessTheOwnerTurnsThemOn_withTheirOwnVariable() {
        assertThat(yaml.getProperty("tiam.pack.cuadernos.sales-enabled")).isEqualTo("${CUADERNOS_SALES_ENABLED:false}");
        assertThat(new PackProperties().getCuadernos().isSalesEnabled()).isFalse();
    }

    @Test
    void theSwitch_isIndependentFromThePacks() {
        PackProperties properties = new PackProperties();

        properties.setSalesEnabled(true);
        assertThat(properties.getCuadernos().isSalesEnabled()).isFalse();

        properties.setSalesEnabled(false);
        properties.getCuadernos().setSalesEnabled(true);
        assertThat(properties.isSalesEnabled()).isFalse();
    }

    @Test
    void price_comesFromTheEnvironment_withTheProvisionalDefault() {
        assertThat(yaml.getProperty("tiam.pack.cuadernos.price-ars")).isEqualTo("${CUADERNOS_PRICE_ARS:14990}");
        assertThat(new PackProperties().getCuadernos().getPriceArs()).isEqualByComparingTo(new BigDecimal("14990"));
    }

    @Test
    void thePacksOwnSettings_areUntouched() {
        assertThat(yaml.getProperty("tiam.pack.sales-enabled")).isEqualTo("${PACK_SALES_ENABLED:false}");
        assertThat(yaml.getProperty("tiam.pack.price-ars")).isEqualTo("${PACK_PRICE_ARS:14990}");
        assertThat(yaml.getProperty("tiam.pack.games-price-ars")).isEqualTo("${PACK_GAMES_PRICE_ARS:10000}");
    }

    @Test
    void theBlueprint_declaresTheNewVariables() throws Exception {
        String render = Files.readString(Path.of("render.yaml"));

        assertThat(render).contains("CUADERNOS_SALES_ENABLED").contains("CUADERNOS_PRICE_ARS");
    }
}
