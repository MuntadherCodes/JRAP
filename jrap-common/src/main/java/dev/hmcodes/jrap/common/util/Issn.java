package dev.hmcodes.jrap.common.util;

import java.util.Locale;

/** ISSN normalisation and checksum validation (feeds gateway check G3 later). */
public final class Issn {

    private Issn() {}

    /** Returns the normalised "NNNN-NNNC" form, or null if the input is not a valid ISSN. */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        if (!digits.matches("\\d{7}[\\dX]")) {
            return null;
        }
        int sum = 0;
        for (int i = 0; i < 7; i++) {
            sum += (digits.charAt(i) - '0') * (8 - i);
        }
        int remainder = sum % 11;
        char expected = remainder == 0 ? '0' : (11 - remainder) == 10 ? 'X' : (char) ('0' + (11 - remainder));
        if (digits.charAt(7) != expected) {
            return null;
        }
        return digits.substring(0, 4) + "-" + digits.substring(4);
    }

    public static boolean isValid(String raw) {
        return normalise(raw) != null;
    }
}
