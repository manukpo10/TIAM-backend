package com.tiam.pack.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The «Cuadernos TIAM» product: 4 workbooks + the complete collection, for A4 and Carta (10 files). */
class CuadernosCatalogTest {

    @Test
    void edition_isTheThirdValue_withItsOwnSlugAndName() {
        assertThat(PackEdition.fromSlug("cuadernos")).contains(PackEdition.CUADERNOS);
        assertThat(PackEdition.fromSlug(" Cuadernos ")).contains(PackEdition.CUADERNOS);
        assertThat(PackEdition.CUADERNOS.slug()).isEqualTo("cuadernos");
        assertThat(PackEdition.CUADERNOS.displayName()).isEqualTo("Cuadernos");
        assertThat(PackEdition.CUADERNOS.isCuadernos()).isTrue();
        assertThat(PackEdition.FAMILIAS.isCuadernos()).isFalse();
        assertThat(PackEdition.PROFESIONALES.isCuadernos()).isFalse();
    }

    @Test
    void productNames_areWhatTheOwnerReadsInAnAlert() {
        assertThat(PackEdition.FAMILIAS.productName()).isEqualTo("Pack Básico familias");
        assertThat(PackEdition.PROFESIONALES.productName()).isEqualTo("Pack Básico profesionales");
        assertThat(PackEdition.CUADERNOS.productName()).isEqualTo("Cuadernos TIAM");
    }

    @Test
    void packEditions_areTheTwoOfTheBasicPack_andNeverTheCuadernos() {
        assertThat(PackEdition.packEditions()).containsExactly(PackEdition.FAMILIAS, PackEdition.PROFESIONALES);
    }

    @Test
    void anOrder_getsTenFiles_theCollectionAndTheFourWorkbooks_forBothPapers() {
        List<PackFile> files = PackCatalog.filesFor(PackEdition.CUADERNOS, false);

        assertThat(files).extracting(PackFile::code).containsExactly(
                "coleccion-completa-a4", "cuaderno-1-a4", "cuaderno-2-a4", "cuaderno-3-a4", "cuaderno-4-a4",
                "coleccion-completa-carta", "cuaderno-1-carta", "cuaderno-2-carta", "cuaderno-3-carta", "cuaderno-4-carta");
        assertThat(files).allSatisfy(f -> assertThat(f.product()).isEqualTo(PackProduct.PACK));
    }

    @Test
    void labels_areTheOnesTheBuyerSees() {
        Map<String, String> expected = Map.of(
                "coleccion-completa-a4", "Colección completa: los 4 cuadernos en 1 PDF",
                "cuaderno-1-a4", "Cuaderno 1 · Nivel inicial",
                "cuaderno-2-a4", "Cuaderno 2 · Nivel intermedio",
                "cuaderno-3-a4", "Cuaderno 3 · Nivel intermedio alto",
                "cuaderno-4-a4", "Cuaderno 4 · Nivel avanzado");

        expected.forEach((code, label) -> assertThat(PackCatalog.find(code, PackEdition.CUADERNOS, false).orElseThrow().label())
                .as(code).isEqualTo(label));
        // same wording for Carta
        assertThat(PackCatalog.find("cuaderno-3-carta", PackEdition.CUADERNOS, false).orElseThrow().label())
                .isEqualTo("Cuaderno 3 · Nivel intermedio alto");
    }

    @Test
    void sizes_areAboutTwentySevenMbTheCollection_andSevenMbEachWorkbook() {
        for (String paper : List.of("a4", "carta")) {
            assertThat(PackCatalog.find("coleccion-completa-" + paper, PackEdition.CUADERNOS, false).orElseThrow().sizeMb())
                    .isEqualTo(27);
            for (int n = 1; n <= 4; n++) {
                assertThat(PackCatalog.find("cuaderno-" + n + "-" + paper, PackEdition.CUADERNOS, false).orElseThrow().sizeMb())
                        .as("cuaderno %d %s", n, paper).isEqualTo(7);
            }
        }
    }

    @Test
    void storageKeys_areVersionedAndSeparatedByPaper() {
        PackFile collection = PackCatalog.find("coleccion-completa-a4", PackEdition.CUADERNOS, false).orElseThrow();
        PackFile third = PackCatalog.find("cuaderno-3-carta", PackEdition.CUADERNOS, false).orElseThrow();
        PackFile fourth = PackCatalog.find("cuaderno-4-a4", PackEdition.CUADERNOS, false).orElseThrow();

        assertThat(collection.storageKey("v1", PackEdition.CUADERNOS)).isEqualTo("packs/v1/cuadernos/a4/coleccion-completa.pdf");
        assertThat(third.storageKey("v1", PackEdition.CUADERNOS)).isEqualTo("packs/v1/cuadernos/carta/cuaderno-3.pdf");
        assertThat(fourth.storageKey("v2", PackEdition.CUADERNOS)).isEqualTo("packs/v2/cuadernos/a4/cuaderno-4.pdf");
    }

    @Test
    void everyStorageKey_isUnique_soNoTwoFilesShareAnObject() {
        List<String> keys = PackCatalog.filesFor(PackEdition.CUADERNOS, false).stream()
                .map(f -> f.storageKey("v1", PackEdition.CUADERNOS)).toList();

        assertThat(keys).doesNotHaveDuplicates().hasSize(10);
    }

    @Test
    void theCuadernillosExtra_neverBelongsToACuadernosOrder_whateverTheFlagSays() {
        assertThat(PackCatalog.filesFor(PackEdition.CUADERNOS, true)).isEqualTo(PackCatalog.filesFor(PackEdition.CUADERNOS, false));
        assertThat(PackCatalog.find("extra-a4", PackEdition.CUADERNOS, true)).isEmpty();
        assertThat(PackCatalog.find("extra-carta", PackEdition.CUADERNOS, true)).isEmpty();
    }

    @Test
    void packFiles_areRefusedToACuadernosOrder_andCuadernosFilesToAPackOrder() {
        assertThat(PackCatalog.find("pack-completo-a4", PackEdition.CUADERNOS, false)).isEmpty();
        assertThat(PackCatalog.find("cuadernillos-carta", PackEdition.CUADERNOS, true)).isEmpty();

        for (PackEdition edition : PackEdition.packEditions()) {
            assertThat(PackCatalog.find("coleccion-completa-a4", edition, true)).as("%s", edition).isEmpty();
            assertThat(PackCatalog.find("cuaderno-1-a4", edition, true)).as("%s", edition).isEmpty();
            assertThat(PackCatalog.find("cuaderno-4-carta", edition, false)).as("%s", edition).isEmpty();
        }
    }

    @Test
    void unknownCodes_areNotFound() {
        assertThat(PackCatalog.find("cuaderno-5-a4", PackEdition.CUADERNOS, false)).isEmpty();
        assertThat(PackCatalog.find("cuaderno-1", PackEdition.CUADERNOS, false)).isEmpty();
        assertThat(PackCatalog.find("../../etc/passwd", PackEdition.CUADERNOS, false)).isEmpty();
        assertThat(PackCatalog.find(null, PackEdition.CUADERNOS, false)).isEmpty();
    }

    @Test
    void downloadFilenames_areAsciiAndSayWhatTheyAre() {
        assertThat(PackCatalog.find("coleccion-completa-a4", PackEdition.CUADERNOS, false).orElseThrow()
                .downloadFilename(PackEdition.CUADERNOS)).isEqualTo("TIAM-Cuadernos-A4-coleccion-completa.pdf");
        assertThat(PackCatalog.find("cuaderno-1-carta", PackEdition.CUADERNOS, false).orElseThrow()
                .downloadFilename(PackEdition.CUADERNOS)).isEqualTo("TIAM-Cuadernos-Carta-cuaderno-1-inicial.pdf");
        assertThat(PackCatalog.find("cuaderno-3-a4", PackEdition.CUADERNOS, false).orElseThrow()
                .downloadFilename(PackEdition.CUADERNOS)).isEqualTo("TIAM-Cuadernos-A4-cuaderno-3-intermedio-alto.pdf");

        for (PackFile file : PackCatalog.filesFor(PackEdition.CUADERNOS, false)) {
            assertThat(file.downloadFilename(PackEdition.CUADERNOS)).matches("[A-Za-z0-9._-]+");
        }
    }

    @Test
    void thePackFilenamesAndKeys_areExactlyWhatTheyWere() {
        PackFile completo = PackCatalog.find("pack-completo-a4", PackEdition.FAMILIAS, false).orElseThrow();

        assertThat(completo.downloadFilename(PackEdition.FAMILIAS)).isEqualTo("TIAM-Pack-Familias-A4-completo.pdf");
        assertThat(completo.storageKey("v1", PackEdition.FAMILIAS)).isEqualTo("packs/v1/familias/a4/pack-completo.pdf");
    }
}
