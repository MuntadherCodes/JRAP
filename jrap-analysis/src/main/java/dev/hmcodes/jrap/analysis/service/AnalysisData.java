package dev.hmcodes.jrap.analysis.service;

import dev.hmcodes.jrap.crawl.domain.Snapshot;
import dev.hmcodes.jrap.extract.domain.Article;
import dev.hmcodes.jrap.extract.domain.AuthorSlot;
import dev.hmcodes.jrap.extract.domain.BoardMember;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.domain.Journal;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

/** Everything the deterministic engine needs, loaded once per audit. */
public record AnalysisData(
        Journal journal,
        List<Article> articles,
        Map<UUID, List<AuthorSlot>> authorsByArticle,
        List<BoardMember> board,
        List<Snapshot> snapshots,
        List<Finding> journalFindings,
        SortedMap<Integer, Long> worksByYear,
        SortedMap<Integer, Long> citedByYear,
        Double twoYearMeanCitedness,
        boolean openAlexAvailable,
        Boolean doajPreservation) {
}
