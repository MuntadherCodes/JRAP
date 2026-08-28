package dev.hmcodes.jrap.analysis.calc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** NFR-MNT-1: the deterministic core of the analysis engine, exhaustively unit-tested. */
class CalculationsTest {

    @Test
    void hhiOfPerfectConcentrationIsOne() {
        assertThat(Calculations.hhi(Map.of("Iraq", 10L))).isEqualTo(1.0);
    }

    @Test
    void hhiOfUniformDistributionIsOneOverN() {
        double hhi = Calculations.hhi(Map.of("A", 5L, "B", 5L, "C", 5L, "D", 5L));
        assertThat(Math.abs(hhi - 0.25) < 1e-9).isTrue();
    }

    @Test
    void hhiOfEmptyIsZero() {
        assertThat(Calculations.hhi(Map.of())).isEqualTo(0.0);
    }

    @Test
    void yearExtractionFromDisplayedDates() {
        assertThat(Calculations.yearOf("2026/03/01")).isEqualTo(2026);
        assertThat(Calculations.yearOf("15 March 2024")).isEqualTo(2024);
        assertThat(Calculations.yearOf("Vol 3")).isNull();
        assertThat(Calculations.yearOf(null)).isNull();
        SortedMap<Integer, Long> counts = Calculations.yearCounts(
                List.of("2024-01-01", "2024-06-01", "2025-01-01", "garbage"));
        assertThat(counts.get(2024)).isEqualTo(2L);
        assertThat(counts.get(2025)).isEqualTo(1L);
        assertThat(counts.size()).isEqualTo(2);
    }

    @Test
    void volumeAnomaliesDetectSpikesAndCollapsesAboveNoiseFloor() {
        SortedMap<Integer, Long> byYear = new TreeMap<>(Map.of(
                2021, 5L, 2022, 12L, 2023, 5L, 2024, 5L, 2025, 2L));
        List<Calculations.VolumeAnomaly> anomalies =
                Calculations.volumeAnomalies(byYear, 2.0, 0.5, 5);
        assertThat(anomalies.size()).isEqualTo(3);
        assertThat(anomalies.get(0).type()).isEqualTo("SPIKE");    // 5 -> 12
        assertThat(anomalies.get(1).type()).isEqualTo("COLLAPSE"); // 12 -> 5
        assertThat(anomalies.get(2).type()).isEqualTo("COLLAPSE"); // 5 -> 2
        // Below the noise floor nothing fires.
        SortedMap<Integer, Long> tiny = new TreeMap<>(Map.of(2024, 1L, 2025, 4L));
        assertThat(Calculations.volumeAnomalies(tiny, 2.0, 0.5, 5)).isEmpty();
    }

    @Test
    void citationTrendClassification() {
        SortedMap<Integer, Long> collapsing = new TreeMap<>(Map.of(
                2022, 40L, 2023, 90L, 2024, 10L, 2025, 8L));
        assertThat(Calculations.citationTrend(collapsing, 2026)).isEqualTo("COLLAPSING");
        SortedMap<Integer, Long> rising = new TreeMap<>(Map.of(
                2022, 10L, 2023, 10L, 2024, 25L, 2025, 30L));
        assertThat(Calculations.citationTrend(rising, 2026)).isEqualTo("RISING");
        SortedMap<Integer, Long> stable = new TreeMap<>(Map.of(
                2022, 20L, 2023, 20L, 2024, 22L, 2025, 19L));
        assertThat(Calculations.citationTrend(stable, 2026)).isEqualTo("STABLE");
        assertThat(Calculations.citationTrend(new TreeMap<>(), 2026)).isEqualTo("UNKNOWN");
    }

    @Test
    void surgeThenCollapseNeedsBothPhases() {
        SortedMap<Integer, Long> surge = new TreeMap<>(Map.of(
                2022, 40L, 2023, 90L, 2024, 10L, 2025, 8L));
        Calculations.SurgeCollapse result = Calculations.surgeThenCollapse(surge, 2.0, 0.5);
        assertThat(result).isNotNull();
        assertThat(result.peakYear()).isEqualTo(2023);
        assertThat(result.after()).isEqualTo(8L);
        SortedMap<Integer, Long> steady = new TreeMap<>(Map.of(
                2022, 40L, 2023, 45L, 2024, 42L));
        assertThat(Calculations.surgeThenCollapse(steady, 2.0, 0.5)).isNull();
    }

    @Test
    void gapYearsAndShares() {
        SortedMap<Integer, Long> byYear = new TreeMap<>(Map.of(2023, 4L, 2025, 6L));
        assertThat(Calculations.gapYears(byYear, 2023, 2025)).isEqualTo(List.of(2024));
        assertThat(Calculations.share(0, 0)).isEqualTo(0.0);
        assertThat(Calculations.share(1, 4)).isEqualTo(0.25);
        assertThat(Calculations.topShare(Map.of("A", 6L, "B", 2L))).isEqualTo(0.75);
        assertThat(Calculations.topShare(Map.of())).isEqualTo(0.0);
    }
}
