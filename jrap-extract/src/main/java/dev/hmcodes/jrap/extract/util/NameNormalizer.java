package dev.hmcodes.jrap.extract.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Author-identity normalisation (FR-EXT-6): case folding, diacritic stripping,
 * "Surname, Given" reordering, punctuation collapse — so board members and repeat
 * authors match across name variants and transliterations in Phase 5.
 */
public final class NameNormalizer {

    private NameNormalizer() {}

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String working = name.trim();
        // Strip titles.
        working = working.replaceAll("(?i)\\b(prof|professor|dr|assist|assoc|asst|mr|mrs|ms|eng)\\.?\\s+", "");
        // "Surname, Given" -> "Given Surname" (single comma only).
        if (working.chars().filter(c -> c == ',').count() == 1) {
            String[] parts = working.split(",", 2);
            working = parts[1].trim() + " " + parts[0].trim();
        }
        String decomposed = Normalizer.normalize(working, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "");
        String cleaned = decomposed.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // Collapse initials-only tokens consistently ("a b hassan" style is stable).
        return Arrays.stream(cleaned.split(" "))
                .filter(token -> !token.isBlank())
                .collect(Collectors.joining(" "));
    }
}
