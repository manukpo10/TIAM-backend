package com.tiam.pack.domain;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What an order is for. The two printable editions of the basic Pack share the layout and the price and differ
 * in the files; {@link #CUADERNOS} is the other main product («Cuadernos TIAM»: 4 workbooks), sold through the
 * same checkout, orders, emails and downloads but with its own files, price and sales switch.
 *
 * <p>Stored by name in {@code pack_orders.edition} (VARCHAR(20), no CHECK), so a new value needs no migration.
 */
public enum PackEdition {
    FAMILIAS("familias", "Familias"),
    PROFESIONALES("profesionales", "Profesionales"),
    CUADERNOS("cuadernos", "Cuadernos");

    private static final List<PackEdition> PACK_EDITIONS = List.of(FAMILIAS, PROFESIONALES);

    private final String slug;
    private final String displayName;

    PackEdition(String slug, String displayName) {
        this.slug = slug;
        this.displayName = displayName;
    }

    /** Lower-case form used in URLs, storage keys and the form field. */
    public String slug() {
        return slug;
    }

    public String displayName() {
        return displayName;
    }

    /** How the owner calls what this order is for, in alerts and logs (never shown to buyers). */
    public String productName() {
        return switch (this) {
            case FAMILIAS -> "Pack Básico familias";
            case PROFESIONALES -> "Pack Básico profesionales";
            case CUADERNOS -> "Cuadernos TIAM";
        };
    }

    /** True for the «Cuadernos TIAM» product, false for the two editions of the basic Pack. */
    public boolean isCuadernos() {
        return this == CUADERNOS;
    }

    /** The editions of the basic Pack (everything except the Cuadernos). */
    public static List<PackEdition> packEditions() {
        return PACK_EDITIONS;
    }

    public static Optional<PackEdition> fromSlug(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        for (PackEdition edition : values()) {
            if (edition.slug.equals(normalized)) {
                return Optional.of(edition);
            }
        }
        return Optional.empty();
    }
}
