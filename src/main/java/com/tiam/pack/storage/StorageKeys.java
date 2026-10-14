package com.tiam.pack.storage;

/** Validation and naming shared by the stores. */
final class StorageKeys {

    private StorageKeys() {
    }

    /** Keys are catalog-generated, but anything that reaches a store is re-checked: no way out of the prefix. */
    static String requireSafe(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key is empty");
        }
        if (key.startsWith("/") || key.contains("\\") || key.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Storage key is not allowed");
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Storage key is not allowed");
            }
        }
        return key;
    }

    /** Keeps a download file name to plain ASCII so it can never break out of the header it ends up in. */
    static String safeFilename(String raw) {
        if (raw == null || raw.isBlank()) {
            return "download";
        }
        StringBuilder out = new StringBuilder();
        for (char c : raw.strip().toCharArray()) {
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == ' ';
            out.append(plain ? c : '_');
        }
        String name = out.toString();
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }
}
