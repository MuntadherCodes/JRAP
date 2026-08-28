package dev.hmcodes.jrap.extract.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Country detection in affiliation strings (FR-EXT-1/2). Canonical English names with
 * common variants; longest-match wins, word-bounded, case-insensitive. Absence returns
 * empty — "not shown" is a legitimate value the diversity metrics must see (FR-EXT-2).
 */
public final class Countries {

    private static final Map<String, String> VARIANTS = new LinkedHashMap<>();
    private static final Map<String, Pattern> PATTERNS = new LinkedHashMap<>();

    static {
        for (Locale locale : Locale.getAvailableLocales()) {
            String iso = locale.getCountry();
            if (iso != null && iso.length() == 2) {
                String name = locale.getDisplayCountry(Locale.ENGLISH);
                if (name != null && !name.isBlank() && !name.equals(iso)) {
                    VARIANTS.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
                }
            }
        }
        // Frequent variants and forms the JDK list doesn't carry.
        variant("usa", "United States");
        variant("u.s.a.", "United States");
        variant("united states of america", "United States");
        variant("uk", "United Kingdom");
        variant("u.k.", "United Kingdom");
        variant("ksa", "Saudi Arabia");
        variant("kingdom of saudi arabia", "Saudi Arabia");
        variant("uae", "United Arab Emirates");
        variant("republic of iraq", "Iraq");
        variant("islamic republic of iran", "Iran");
        variant("iran", "Iran");
        variant("syria", "Syria");
        variant("south korea", "South Korea");
        variant("republic of korea", "South Korea");
        variant("russia", "Russia");
        variant("czech republic", "Czechia");
        variant("türkiye", "Turkey");
        variant("turkiye", "Turkey");
        variant("viet nam", "Vietnam");
        variant("palestine", "Palestine");
        VARIANTS.forEach((variantName, canonical) -> PATTERNS.put(variantName, Pattern.compile(
                "(?i)(?<![\\p{L}])" + Pattern.quote(variantName) + "(?![\\p{L}])")));
    }

    private static void variant(String variantName, String canonical) {
        VARIANTS.put(variantName, canonical);
    }

    private Countries() {}

    /** Finds the country stated in an affiliation string; longest variant wins. */
    public static Optional<String> find(String affiliation) {
        if (affiliation == null || affiliation.isBlank()) {
            return Optional.empty();
        }
        String bestVariant = null;
        for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
            if (entry.getValue().matcher(affiliation).find()
                    && (bestVariant == null || entry.getKey().length() > bestVariant.length())) {
                bestVariant = entry.getKey();
            }
        }
        return Optional.ofNullable(bestVariant).map(VARIANTS::get);
    }
}
