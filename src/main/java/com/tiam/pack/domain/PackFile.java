package com.tiam.pack.domain;

/**
 * One downloadable file of the catalog.
 *
 * @param code         stable identifier the API exposes ("pack-completo-a4"); never the storage key
 * @param product      the pack itself or the extra booklet
 * @param paper        A4 or Carta
 * @param label        buyer-facing description (Spanish)
 * @param storageName  file name under {@code packs/<version>/<edition>/<paper>/}
 * @param downloadKind short word used in the download file name ("completo", "cuadernillos", "cuaderno-1-inicial");
 *                     null for the extra
 * @param sizeMb       approximate size, shown so the buyer knows what a download weighs
 */
public record PackFile(
        String code,
        PackProduct product,
        PackPaper paper,
        String label,
        String storageName,
        String downloadKind,
        int sizeMb) {

    /** {@code packs/v1/familias/a4/pack-completo.pdf}. The version belongs to the ORDER, not to the catalog. */
    public String storageKey(String catalogVersion, PackEdition edition) {
        return "packs/" + catalogVersion + "/" + edition.slug() + "/" + paper.slug() + "/" + storageName;
    }

    /** ASCII-only name the browser saves the file as. */
    public String downloadFilename(PackEdition edition) {
        String extension = storageName.substring(storageName.lastIndexOf('.'));
        if (edition.isCuadernos()) {
            return "TIAM-Cuadernos-" + paper.displayName() + "-" + downloadKind + extension;
        }
        if (product == PackProduct.EXTRA) {
            return "TIAM-Cuadernillo-extra-" + edition.displayName() + "-" + paper.displayName() + extension;
        }
        return "TIAM-Pack-" + edition.displayName() + "-" + paper.displayName() + "-" + downloadKind + extension;
    }
}
