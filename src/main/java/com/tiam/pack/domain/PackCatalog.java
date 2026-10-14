package com.tiam.pack.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The fixed set of files a pack order gives access to. It lives in code (not in a table) on purpose:
 * 12 objects that change a few times a year, versioned through the storage key
 * ({@code packs/v1/...} to {@code packs/v2/...}) so replacing a PDF never breaks an old order.
 *
 * <p>An order gets the pack files of ITS edition for both papers and, when it bought the extra
 * booklet, the extra of the SAME edition.
 */
public final class PackCatalog {

    private static final List<PackFile> PACK_FILES = buildPackFiles();
    private static final List<PackFile> EXTRA_FILES = buildExtraFiles();

    private PackCatalog() {
    }

    public static List<PackFile> filesFor(PackEdition edition, boolean withExtra) {
        List<PackFile> files = new ArrayList<>(PACK_FILES);
        if (withExtra) {
            files.addAll(EXTRA_FILES);
        }
        return List.copyOf(files);
    }

    /** The file with that code, only if this order is entitled to it. */
    public static Optional<PackFile> find(String code, PackEdition edition, boolean withExtra) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return filesFor(edition, withExtra).stream().filter(file -> file.code().equals(code)).findFirst();
    }

    private static List<PackFile> buildPackFiles() {
        List<PackFile> files = new ArrayList<>();
        for (PackPaper paper : PackPaper.values()) {
            files.add(new PackFile("pack-completo-" + paper.slug(), PackProduct.PACK, paper,
                    "Pack completo (1 PDF)", "pack-completo.pdf", "completo", 19));
            files.add(new PackFile("cuadernillos-" + paper.slug(), PackProduct.PACK, paper,
                    "Cuadernillos sueltos (ZIP)", "cuadernillos.zip", "cuadernillos", 38));
        }
        return List.copyOf(files);
    }

    private static List<PackFile> buildExtraFiles() {
        List<PackFile> files = new ArrayList<>();
        for (PackPaper paper : PackPaper.values()) {
            files.add(new PackFile("extra-" + paper.slug(), PackProduct.EXTRA, paper,
                    "Cuadernillo extra (PDF)", "extra-1.pdf", null, 7));
        }
        return List.copyOf(files);
    }
}
