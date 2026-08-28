package dev.hmcodes.jrap.analysis.rubric;

import java.util.Map;

/**
 * The versioned scoring rubric (§5.2): thresholds and per-criterion score deltas are
 * CONFIGURATION, not code. Every audit freezes the version it used (§3.3); v1.0 is
 * seeded from the golden audit so WJCM scores 2/2/1/4/3 (calibrated at AC-1, Phase 9).
 */
public record Rubric(String version, Map<String, Double> thresholds, Map<String, Integer> deltas) {

    public double threshold(String name) {
        Double value = thresholds.get(name);
        if (value == null) {
            throw new IllegalStateException("Rubric " + version + " missing threshold: " + name);
        }
        return value;
    }

    public int delta(String name) {
        Integer value = deltas.get(name);
        if (value == null) {
            throw new IllegalStateException("Rubric " + version + " missing delta: " + name);
        }
        return value;
    }
}
