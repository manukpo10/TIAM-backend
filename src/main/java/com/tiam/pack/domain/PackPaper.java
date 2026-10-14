package com.tiam.pack.domain;

public enum PackPaper {
    A4("a4", "A4"),
    CARTA("carta", "Carta");

    private final String slug;
    private final String displayName;

    PackPaper(String slug, String displayName) {
        this.slug = slug;
        this.displayName = displayName;
    }

    public String slug() {
        return slug;
    }

    public String displayName() {
        return displayName;
    }
}
