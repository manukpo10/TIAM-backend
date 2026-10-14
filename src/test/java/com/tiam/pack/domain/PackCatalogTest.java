package com.tiam.pack.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PackCatalogTest {

    @Test
    void packOnlyOrder_getsTheTwoPackFilesForEachPaper() {
        List<PackFile> files = PackCatalog.filesFor(PackEdition.FAMILIAS, false);

        assertThat(files).extracting(PackFile::code).containsExactlyInAnyOrder(
                "pack-completo-a4", "cuadernillos-a4", "pack-completo-carta", "cuadernillos-carta");
        assertThat(files).allSatisfy(f -> assertThat(f.product()).isEqualTo(PackProduct.PACK));
    }

    @Test
    void orderWithExtra_alsoGetsTheExtraBookletForBothPapers() {
        List<PackFile> files = PackCatalog.filesFor(PackEdition.PROFESIONALES, true);

        assertThat(files).extracting(PackFile::code).contains("extra-a4", "extra-carta");
        assertThat(files).hasSize(6);
    }

    @Test
    void shownSizes_matchWhatTheRealFilesWeigh() {
        // The per-paper ZIP weighs 37.7 to 37.9 MB; showing 32 made buyers expect a lighter download than they get.
        for (PackEdition edition : PackEdition.values()) {
            for (String paper : List.of("a4", "carta")) {
                assertThat(PackCatalog.find("pack-completo-" + paper, edition, true).orElseThrow().sizeMb()).isEqualTo(19);
                assertThat(PackCatalog.find("cuadernillos-" + paper, edition, true).orElseThrow().sizeMb()).isEqualTo(38);
                assertThat(PackCatalog.find("extra-" + paper, edition, true).orElseThrow().sizeMb()).isEqualTo(7);
            }
        }
    }

    @Test
    void storageKeys_areVersionedAndSeparatedByEditionAndPaper() {
        PackFile completo = PackCatalog.find("pack-completo-a4", PackEdition.FAMILIAS, false).orElseThrow();
        PackFile cuadernillos = PackCatalog.find("cuadernillos-carta", PackEdition.PROFESIONALES, false).orElseThrow();
        PackFile extra = PackCatalog.find("extra-carta", PackEdition.PROFESIONALES, true).orElseThrow();

        assertThat(completo.storageKey("v1", PackEdition.FAMILIAS)).isEqualTo("packs/v1/familias/a4/pack-completo.pdf");
        assertThat(cuadernillos.storageKey("v1", PackEdition.PROFESIONALES))
                .isEqualTo("packs/v1/profesionales/carta/cuadernillos.zip");
        assertThat(extra.storageKey("v2", PackEdition.PROFESIONALES)).isEqualTo("packs/v2/profesionales/carta/extra-1.pdf");
    }

    @Test
    void extraFile_isNotAvailableToAnOrderThatDidNotBuyIt() {
        assertThat(PackCatalog.find("extra-a4", PackEdition.FAMILIAS, false)).isEmpty();
    }

    @Test
    void unknownCode_isNotFound() {
        assertThat(PackCatalog.find("../../etc/passwd", PackEdition.FAMILIAS, true)).isEmpty();
        assertThat(PackCatalog.find("", PackEdition.FAMILIAS, true)).isEmpty();
        assertThat(PackCatalog.find(null, PackEdition.FAMILIAS, true)).isEmpty();
    }

    @Test
    void downloadFilenames_areAsciiAndSayWhatTheyAre() {
        PackFile completo = PackCatalog.find("pack-completo-a4", PackEdition.FAMILIAS, false).orElseThrow();
        PackFile extra = PackCatalog.find("extra-carta", PackEdition.PROFESIONALES, true).orElseThrow();

        assertThat(completo.downloadFilename(PackEdition.FAMILIAS)).isEqualTo("TIAM-Pack-Familias-A4-completo.pdf");
        assertThat(extra.downloadFilename(PackEdition.PROFESIONALES))
                .isEqualTo("TIAM-Cuadernillo-extra-Profesionales-Carta.pdf");
    }

    @Test
    void editionSlugs_roundTrip() {
        assertThat(PackEdition.fromSlug("familias")).contains(PackEdition.FAMILIAS);
        assertThat(PackEdition.fromSlug(" Profesionales ")).contains(PackEdition.PROFESIONALES);
        assertThat(PackEdition.fromSlug("otra")).isEmpty();
        assertThat(PackEdition.fromSlug(null)).isEmpty();
        assertThat(PackEdition.FAMILIAS.slug()).isEqualTo("familias");
    }
}
