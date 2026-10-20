package com.tiam.pack.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.tiam.common.email.EmailSender;
import com.tiam.pack.domain.PackCatalog;
import com.tiam.pack.domain.PackEdition;
import com.tiam.pack.storage.PrivateFileStore;
import com.tiam.testsupport.LogCapture;
import com.tiam.testsupport.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Per-product readiness: a product that is for sale needs its prices to be sane and EVERY object of its catalog to be
 * in R2, otherwise sales of THAT product stay closed (and the log says why). Checked against a fake store.
 */
class PackSalesReadinessProductTest {

    /** A remote store whose contents the test controls and whose questions it counts. */
    static final class FakeStore implements PrivateFileStore {
        final Set<String> existing = new HashSet<>();
        boolean remote = true;
        int asked;

        @Override
        public String signedDownloadUrl(String key, Duration ttl, String downloadFilename) {
            return "https://example.com/" + key;
        }

        @Override
        public boolean isRemote() {
            return remote;
        }

        @Override
        public List<String> missingKeys(Collection<String> keys) {
            asked++;
            return keys.stream().filter(key -> !existing.contains(key)).toList();
        }

        void upload(PackEdition edition, String version) {
            PackCatalog.filesFor(edition, true).forEach(file -> existing.add(file.storageKey(version, edition)));
        }

        void remove(PackEdition edition, String version, String code) {
            existing.remove(PackCatalog.find(code, edition, true).orElseThrow().storageKey(version, edition));
        }
    }

    PackProperties properties;
    FakeStore store;
    MutableClock clock;
    MockEnvironment environment;

    @BeforeEach
    void setUp() {
        properties = new PackProperties();
        store = new FakeStore();
        clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
        environment = new MockEnvironment();
        for (PackEdition edition : PackEdition.values()) {
            store.upload(edition, "v1");
        }
    }

    // --- files -----------------------------------------------------------------------------------------------------

    @Test
    void whenEveryObjectIsInR2_noProductHasAProblem() {
        for (PackEdition edition : PackEdition.values()) {
            assertThat(readiness().productProblems(edition)).as("%s", edition).isEmpty();
        }
    }

    @Test
    void aMissingCuadernosFile_closesTheCuadernos_namingTheProductAndTheKey() {
        store.remove(PackEdition.CUADERNOS, "v1", "cuaderno-3-carta");

        List<String> problems = readiness().productProblems(PackEdition.CUADERNOS);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("Cuadernos TIAM").contains("packs/v1/cuadernos/carta/cuaderno-3.pdf")
                .contains("1 of 10").contains("R2");
    }

    @Test
    void everyProductIsCheckedOnItsOwn_aGapInOneDoesNotCloseTheOthers() {
        store.remove(PackEdition.CUADERNOS, "v1", "cuaderno-1-a4");
        store.remove(PackEdition.FAMILIAS, "v1", "pack-completo-a4");

        PackSalesReadiness readiness = readiness();

        assertThat(readiness.productProblems(PackEdition.CUADERNOS)).hasSize(1);
        assertThat(readiness.productProblems(PackEdition.FAMILIAS)).hasSize(1).first().asString().contains("Pack Básico familias");
        assertThat(readiness.productProblems(PackEdition.PROFESIONALES)).isEmpty();
    }

    @Test
    void thePacksExtraBookletIsPartOfItsCatalog_soItMustExistToo() {
        store.remove(PackEdition.PROFESIONALES, "v1", "extra-carta");

        assertThat(readiness().productProblems(PackEdition.PROFESIONALES)).hasSize(1)
                .first().asString().contains("packs/v1/profesionales/carta/extra-1.pdf");
    }

    @Test
    void theCuadernosFilesAreLookedUpUnderTheCuadernosCatalogVersion_notThePacks() {
        properties.setCatalogVersion("v9"); // the Pack moved on; its v9 files are not uploaded yet
        PackSalesReadiness readiness = readiness();

        assertThat(readiness.productProblems(PackEdition.CUADERNOS)).isEmpty();      // still v1
        assertThat(readiness.productProblems(PackEdition.FAMILIAS)).hasSize(1);     // v9 is missing
        store.upload(PackEdition.FAMILIAS, "v9");
        clock.advance(Duration.ofMinutes(1));
        assertThat(readiness.productProblems(PackEdition.FAMILIAS)).isEmpty();

        properties.getCuadernos().setCatalogVersion("v2");
        assertThat(readiness.productProblems(PackEdition.CUADERNOS)).hasSize(1)
                .first().asString().contains("packs/v2/cuadernos/");
    }

    @Test
    void devMode_doesNotRequireTheFiles_andNeverAsksTheStore() {
        properties.setDevMode(true);
        store.existing.clear();

        assertThat(readiness().productProblems(PackEdition.CUADERNOS)).isEmpty();
        assertThat(store.asked).isZero();
    }

    @Test
    void devModeInProduction_isIgnored_soTheFilesAreRequired() {
        properties.setDevMode(true);
        environment.setActiveProfiles("prod");
        store.existing.clear();

        assertThat(readiness().productProblems(PackEdition.CUADERNOS)).hasSize(1);
    }

    @Test
    void aStoreThatIsNotRemote_isReportedByTheGeneralCheck_notRepeatedHere() {
        store.remote = false;
        store.existing.clear();

        assertThat(readiness().productProblems(PackEdition.CUADERNOS)).isEmpty();
        assertThat(store.asked).isZero();
    }

    // --- caching and logging -----------------------------------------------------------------------------------------

    @Test
    void aCompleteCatalog_isNotAskedAgainEveryCheckout_butIsRecheckedLater() {
        PackSalesReadiness readiness = readiness();

        readiness.productProblems(PackEdition.CUADERNOS);
        readiness.productProblems(PackEdition.CUADERNOS);
        clock.advance(Duration.ofMinutes(9));
        readiness.productProblems(PackEdition.CUADERNOS);
        assertThat(store.asked).as("cached for minutes").isEqualTo(1);

        clock.advance(Duration.ofMinutes(2));
        readiness.productProblems(PackEdition.CUADERNOS);
        assertThat(store.asked).isEqualTo(2);
    }

    @Test
    void aGap_isRecheckedSoonerSoTheOwnerCanReopenByUploading_withoutARestart() {
        store.remove(PackEdition.CUADERNOS, "v1", "cuaderno-2-a4");
        PackSalesReadiness readiness = readiness();

        assertThat(readiness.productProblems(PackEdition.CUADERNOS)).hasSize(1);
        clock.advance(Duration.ofSeconds(10));
        assertThat(readiness.productProblems(PackEdition.CUADERNOS)).hasSize(1);
        assertThat(store.asked).as("not hammering R2 on every checkout").isEqualTo(1);

        store.upload(PackEdition.CUADERNOS, "v1");  // the owner uploads the missing file
        clock.advance(Duration.ofSeconds(30));
        assertThat(readiness.productProblems(PackEdition.CUADERNOS)).isEmpty();
        assertThat(store.asked).isEqualTo(2);
    }

    @Test
    void aFreshCheckThatFindsAGap_logsOneClearError_andACachedAnswerStaysQuiet() {
        store.remove(PackEdition.CUADERNOS, "v1", "cuaderno-4-carta");
        PackSalesReadiness readiness = readiness();

        try (LogCapture logs = new LogCapture(PackSalesReadiness.class)) {
            readiness.productProblems(PackEdition.CUADERNOS);
            readiness.productProblems(PackEdition.CUADERNOS);

            assertThat(logs.messages(Level.ERROR)).hasSize(1);
            assertThat(logs.messages(Level.ERROR).get(0)).contains("Cuadernos TIAM").contains("cuaderno-4.pdf")
                    .contains("closed");
        }
    }

    // --- prices ------------------------------------------------------------------------------------------------------------

    @Test
    void aCuadernosPriceBelowAThousand_closesTheCuadernos_namingTheVariable() {
        properties.getCuadernos().setPriceArs(new BigDecimal("20")); // "20.000" typed with a thousands dot is read as 20

        List<String> problems = readiness().productProblems(PackEdition.CUADERNOS);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("CUADERNOS_PRICE_ARS").contains("20").contains("1000");
    }

    @Test
    void zeroNegativeAndMissingPrices_areImpossible_gamesPricesIncluded() {
        for (BigDecimal bad : new BigDecimal[] {BigDecimal.ZERO, new BigDecimal("-5"), null}) {
            properties.getCuadernos().setPriceArs(bad);
            properties.getCuadernos().setGamesPriceArs(bad);

            assertThat(readiness().productProblems(PackEdition.CUADERNOS)).as("price %s", bad)
                    .anyMatch(p -> p.contains("CUADERNOS_PRICE_ARS")).anyMatch(p -> p.contains("CUADERNOS_GAMES_PRICE_ARS"));
        }
    }

    @Test
    void theBoundary_isAThousandPesos() {
        properties.getCuadernos().setPriceArs(new BigDecimal("1000"));
        properties.getCuadernos().setGamesPriceArs(new BigDecimal("1000"));
        assertThat(readiness().productProblems(PackEdition.CUADERNOS)).isEmpty();

        properties.getCuadernos().setGamesPriceArs(new BigDecimal("999.99"));
        assertThat(readiness().productProblems(PackEdition.CUADERNOS)).hasSize(1)
                .first().asString().contains("CUADERNOS_GAMES_PRICE_ARS");
    }

    @Test
    void everyPackPriceIsChecked_theMainOne_theGamesOne_andTheExtraOne() {
        properties.setPriceArs(new BigDecimal("15"));
        properties.setGamesPriceArs(new BigDecimal("10"));
        properties.setExtraPriceArs(new BigDecimal("5"));

        for (PackEdition edition : PackEdition.packEditions()) {
            assertThat(readiness().productProblems(edition)).as("%s", edition)
                    .anyMatch(p -> p.contains("PACK_PRICE_ARS"))
                    .anyMatch(p -> p.contains("PACK_GAMES_PRICE_ARS"))
                    .anyMatch(p -> p.contains("PACK_EXTRA_PRICE_ARS"));
        }
    }

    @Test
    void aBadPriceOfOneProduct_neverClosesTheOther() {
        properties.getCuadernos().setPriceArs(new BigDecimal("20"));
        PackSalesReadiness readiness = readiness();
        assertThat(readiness.productProblems(PackEdition.FAMILIAS)).isEmpty();
        assertThat(readiness.productProblems(PackEdition.PROFESIONALES)).isEmpty();

        properties.getCuadernos().setPriceArs(new BigDecimal("20000"));
        properties.setPriceArs(new BigDecimal("14"));
        assertThat(readiness.productProblems(PackEdition.CUADERNOS)).isEmpty();
        assertThat(readiness.productProblems(PackEdition.FAMILIAS)).isNotEmpty();
    }

    @Test
    void thePriceRuleAppliesInDevModeToo_andNeverTouchesTheStore() {
        properties.setDevMode(true);
        properties.getCuadernos().setPriceArs(new BigDecimal("20"));

        assertThat(readiness().productProblems(PackEdition.CUADERNOS)).anyMatch(p -> p.contains("CUADERNOS_PRICE_ARS"));
        assertThat(store.asked).isZero();
    }

    @Test
    void theLaunchPrices_areAllFine() {
        for (PackEdition edition : PackEdition.values()) {
            assertThat(readiness().productProblems(edition)).as("%s", edition).isEmpty();
        }
        assertThat(properties.getCuadernos().getPriceArs()).isEqualByComparingTo("20000");
        assertThat(properties.getCuadernos().getGamesPriceArs()).isEqualByComparingTo("15000");
    }

    private PackSalesReadiness readiness() {
        EmailSender email = mock(EmailSender.class);
        when(email.isEnabled()).thenReturn(true);
        return new PackSalesReadiness(properties, store, email, environment, clock);
    }
}
