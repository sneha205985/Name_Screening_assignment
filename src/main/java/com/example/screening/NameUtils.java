package com.example.screening;

import java.text.Normalizer;

public final class NameUtils {
    private NameUtils() {}

    public static String normalize(String s) {
        if (s == null) return "";
        String lower = s.trim().toLowerCase();

        // Normalize unicode accents (optional but professional)
        String noAccents = Normalizer.normalize(lower, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");

        // Replace punctuation with space, keep letters/digits/spaces
        String cleaned = noAccents.replaceAll("[^a-z0-9\\s]+", " ");

        // Collapse spaces
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    public static String tokenSort(String normalized) {
        if (normalized == null || normalized.isBlank()) return "";
        String[] parts = normalized.split(" ");
        java.util.Arrays.sort(parts);
        return String.join(" ", parts);
    }
}