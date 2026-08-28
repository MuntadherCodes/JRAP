package dev.hmcodes.jrap.extract.util;

import java.util.Locale;
import java.util.Set;

/**
 * Lightweight abstract-language detection (FR-EXT-3): script first, then an English
 * stop-word ratio for Latin text. Deterministic by design — good enough to feed
 * gateway check G4; ambiguous cases resolve to "other", never a guess.
 */
public final class LanguageDetector {

    private static final Set<String> ENGLISH_STOPWORDS = Set.of(
            "the", "of", "and", "in", "to", "a", "is", "for", "with", "on", "this",
            "that", "are", "was", "were", "by", "as", "an", "be", "from", "at", "or",
            "which", "these", "results", "study", "using", "between", "we", "our");

    private LanguageDetector() {}

    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            return "unknown";
        }
        if (ScriptDetector.arabicShare(text) >= 0.5) {
            return "ar";
        }
        if (ScriptDetector.romanShare(text) < 0.5) {
            return "other";
        }
        String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+");
        if (words.length < 8) {
            return "unknown";
        }
        long stopwords = 0;
        for (String word : words) {
            if (ENGLISH_STOPWORDS.contains(word)) {
                stopwords++;
            }
        }
        return (double) stopwords / words.length >= 0.08 ? "en" : "other";
    }
}
