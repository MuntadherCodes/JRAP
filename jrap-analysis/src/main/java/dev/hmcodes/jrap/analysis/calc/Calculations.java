package dev.hmcodes.jrap.analysis.calc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, deterministic calculations for the analysis engine (FR-ANL-2/3/4, §5).
 * No I/O, no state: same inputs, same outputs — property-tested in isolation
 * (NFR-MNT-1 demands >= 80% coverage on this engine).
 */
public final class Calculations {

    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");

    private Calculations() {}

    /** Herfindahl–Hirschman Index over a count distribution: sum of squared shares, 0..1. */
    public static double hhi(Map<String, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            return 0;
        }
        double sum = 0;
        for (long count : counts.values()) {
            double share = (double) count / total;
            sum += share * share;
        }
        return sum;
    }

    /** Extracts a 4-digit year from a displayed date string; null when absent. */
    public static Integer yearOf(String displayedDate) {
        if (displayedDate == null) {
            return null;
        }
        Matcher matcher = YEAR.matcher(displayedDate);
        return matcher.find() ? Integer.parseInt(matcher.group()) : null;
    }

    /** Counts values per extracted year, sorted ascending. */
    public static SortedMap<Integer, Long> yearCounts(List<String> displayedDates) {
        SortedMap<Integer, Long> counts = new TreeMap<>();
        for (String date : displayedDates) {
            Integer year = yearOf(date);
            if (year != null) {
                counts.merge(year, 1L, Long::sum);
            }
        }
        return counts;
    }

    public record VolumeAnomaly(int year, long previous, long current, String type) {}

    /**
     * RF-01: year-over-year spikes (> spikeFactor x) and collapses (< collapseFactor x),
     * ignoring years whose base volume is below minBase (noise guard).
     */
    public static List<VolumeAnomaly> volumeAnomalies(SortedMap<Integer, Long> byYear,
                                                      double spikeFactor, double collapseFactor,
                                                      long minBase) {
        List<VolumeAnomaly> anomalies = new ArrayList<>();
        Integer previousYear = null;
        for (Map.Entry<Integer, Long> entry : byYear.entrySet()) {
            if (previousYear != null && entry.getKey() == previousYear + 1) {
                long previous = byYear.get(previousYear);
                long current = entry.getValue();
                if (previous >= minBase && current > previous * spikeFactor) {
                    anomalies.add(new VolumeAnomaly(entry.getKey(), previous, current, "SPIKE"));
                }
                if (previous >= minBase && current < previous * collapseFactor) {
                    anomalies.add(new VolumeAnomaly(entry.getKey(), previous, current, "COLLAPSE"));
                }
            }
            previousYear = entry.getKey();
        }
        return anomalies;
    }

    /**
     * FR-ANL-4 trend classification over citations received per year: the last two FULL
     * years against the two before them. COLLAPSING < 0.5x, RISING > 1.5x, else STABLE;
     * UNKNOWN when there is not enough history.
     */
    public static String citationTrend(SortedMap<Integer, Long> citedByYear, int currentYear) {
        long recent = sumYears(citedByYear, currentYear - 2, currentYear - 1);
        long prior = sumYears(citedByYear, currentYear - 4, currentYear - 3);
        if (prior == 0 && recent == 0) {
            return "UNKNOWN";
        }
        if (prior == 0) {
            return "RISING";
        }
        double ratio = (double) recent / prior;
        if (ratio < 0.5) {
            return "COLLAPSING";
        }
        if (ratio > 1.5) {
            return "RISING";
        }
        return "STABLE";
    }

    public record SurgeCollapse(int peakYear, long peak, long after) {}

    /**
     * RF-02: a citation peak at least surgeFactor x the mean of the years before it,
     * followed by a fall below collapseFactor x the peak. Null when absent.
     */
    public static SurgeCollapse surgeThenCollapse(SortedMap<Integer, Long> citedByYear,
                                                  double surgeFactor, double collapseFactor) {
        List<Map.Entry<Integer, Long>> entries = new ArrayList<>(citedByYear.entrySet());
        for (int i = 1; i < entries.size() - 1; i++) {
            long peak = entries.get(i).getValue();
            double meanBefore = entries.subList(0, i).stream()
                    .mapToLong(Map.Entry::getValue).average().orElse(0);
            long minAfter = entries.subList(i + 1, entries.size()).stream()
                    .mapToLong(Map.Entry::getValue).min().orElse(Long.MAX_VALUE);
            if (meanBefore > 0 && peak >= meanBefore * surgeFactor
                    && minAfter < peak * collapseFactor) {
                return new SurgeCollapse(entries.get(i).getKey(), peak, minAfter);
            }
        }
        return null;
    }

    /** Share helper that returns 0 for an empty denominator (never NaN). */
    public static double share(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    /** Top-share of a distribution (largest single bucket / total), 0 for empty. */
    public static double topShare(Map<String, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        return share(max, total);
    }

    /** Calendar years in [firstYear, lastYear] that have zero entries in byYear. */
    public static List<Integer> gapYears(SortedMap<Integer, Long> byYear, int firstYear, int lastYear) {
        List<Integer> gaps = new ArrayList<>();
        for (int year = firstYear; year <= lastYear; year++) {
            if (byYear.getOrDefault(year, 0L) == 0) {
                gaps.add(year);
            }
        }
        return gaps;
    }

    /** Normalised distribution map builder preserving insertion by descending count. */
    public static Map<String, Long> sortedByCountDesc(Map<String, Long> counts) {
        Map<String, Long> sorted = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static long sumYears(SortedMap<Integer, Long> byYear, int from, int to) {
        long sum = 0;
        for (int year = from; year <= to; year++) {
            sum += byYear.getOrDefault(year, 0L);
        }
        return sum;
    }
}
