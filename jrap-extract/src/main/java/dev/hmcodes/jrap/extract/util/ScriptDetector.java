package dev.hmcodes.jrap.extract.util;

/** Unicode-script classification for titles, names, abstracts, references (FR-EXT-3). */
public final class ScriptDetector {

    private ScriptDetector() {}

    public static String classify(String text) {
        if (text == null || text.isBlank()) {
            return "UNKNOWN";
        }
        double roman = romanShare(text);
        double arabic = arabicShare(text);
        if (roman >= 0.9) {
            return "ROMAN";
        }
        if (arabic >= 0.5) {
            return "ARABIC";
        }
        if (roman >= 0.4 && arabic >= 0.1) {
            return "MIXED";
        }
        return roman > arabic ? "ROMAN" : "OTHER";
    }

    /** Share of letters that are Latin script (feeds gateway checks G4/G6 in Phase 5). */
    public static double romanShare(String text) {
        return share(text, Character.UnicodeScript.LATIN);
    }

    public static double arabicShare(String text) {
        return share(text, Character.UnicodeScript.ARABIC);
    }

    private static double share(String text, Character.UnicodeScript script) {
        if (text == null) {
            return 0;
        }
        long letters = 0;
        long matching = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (Character.isLetter(codePoint)) {
                letters++;
                if (Character.UnicodeScript.of(codePoint) == script) {
                    matching++;
                }
            }
            i += Character.charCount(codePoint);
        }
        return letters == 0 ? 0 : (double) matching / letters;
    }
}
