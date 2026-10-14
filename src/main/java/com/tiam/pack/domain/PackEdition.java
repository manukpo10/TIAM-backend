package com.tiam.pack.domain;

import java.util.Locale;
import java.util.Optional;

/** The two printable editions. They share the layout and the price; they differ in the files. */
public enum PackEdition {
    FAMILIAS("familias", "Familias"),
    PROFESIONALES("profesionales", "Profesionales");

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
