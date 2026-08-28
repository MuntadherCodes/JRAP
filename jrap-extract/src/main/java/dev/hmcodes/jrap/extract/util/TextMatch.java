package dev.hmcodes.jrap.extract.util;

import java.text.Normalizer;
import java.util.Locale;

/** Normalised text comparison used by cross-source reconciliation (FR-EXT-5). */
public final class TextMatch {

    private TextMatch() {}

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{Alnum}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** True when either normalised string contains the other (tolerates subtitles). */
    public static boolean roughlyEqual(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.contains(right) || right.contains(left);
    }
}
