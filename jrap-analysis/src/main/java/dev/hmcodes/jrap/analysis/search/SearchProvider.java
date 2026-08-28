package dev.hmcodes.jrap.analysis.search;

import java.util.List;

/**
 * Pluggable web-search adapter (FR-INT-5) used by the copied-text (RF-07) and
 * misattributed-authorship (RF-06) detectors, with per-audit query budgets.
 * Disabled by default: dependent checks degrade to UNCLEAR, never guesses.
 */
public interface SearchProvider {

    record SearchHit(String title, String url, String snippet) {}

    List<SearchHit> search(String query, int limit);

    boolean isEnabled();
}
