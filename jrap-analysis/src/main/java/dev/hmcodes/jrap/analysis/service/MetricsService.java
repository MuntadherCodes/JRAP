package dev.hmcodes.jrap.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.analysis.calc.Calculations;
import dev.hmcodes.jrap.analysis.domain.AnalysisMetric;
import dev.hmcodes.jrap.analysis.repo.AnalysisMetricRepository;
import dev.hmcodes.jrap.analysis.rubric.Rubric;
import dev.hmcodes.jrap.extract.domain.Article;
import dev.hmcodes.jrap.extract.domain.AuthorSlot;
import dev.hmcodes.jrap.extract.domain.BoardMember;
import dev.hmcodes.jrap.extract.util.TextMatch;
import dev.hmcodes.jrap.registry.domain.Audit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

/**
 * FR-ANL-2/3/4: regularity, diversity and citation-standing metrics, persisted as
 * analysis_metric rows and returned for the gateway checks, detectors and scorer.
 */
@Service
public class MetricsService {

    public record MetricValue(Double value, Map<String, Object> detail) {}

    private final AnalysisMetricRepository metricRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public MetricsService(AnalysisMetricRepository metricRepository, ObjectMapper objectMapper,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.metricRepository = metricRepository;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Map<String, MetricValue> compute(Audit audit, AnalysisData data, Rubric rubric) {
        Map<String, MetricValue> metrics = new LinkedHashMap<>();
        int currentYear = clock.instant().atZone(ZoneOffset.UTC).getYear();

        // ---- regularity (FR-ANL-2)
        List<String> publishedDates = data.articles().stream().map(Article::getDatePublished).toList();
        SortedMap<Integer, Long> articlesByYear = Calculations.yearCounts(publishedDates);
        // Prefer the (much longer) OpenAlex works history when available; the crawl only
        // sees what the current site links.
        SortedMap<Integer, Long> volumeByYear = data.worksByYear().isEmpty()
                ? articlesByYear : data.worksByYear();
        metrics.put("articles_total", new MetricValue((double) data.articles().size(), Map.of()));
        metrics.put("articles_by_year", new MetricValue(null, Map.of("crawl", articlesByYear,
                "openalex", data.worksByYear())));
        List<Integer> gaps = Calculations.gapYears(volumeByYear, currentYear - 3, currentYear - 1);
        if (volumeByYear.isEmpty()) {
            gaps = List.of(); // no volume data at all -> handled as UNCLEAR, not as gaps
        }
        metrics.put("publication_gap_years", new MetricValue((double) gaps.size(), Map.of("years", gaps)));

        long issuePages = data.snapshots().stream().filter(s -> "issue".equals(s.getPageType())).count();
        double avgPerIssue = issuePages == 0 ? 0 : (double) data.articles().size() / issuePages;
        metrics.put("avg_articles_per_issue", new MetricValue(round(avgPerIssue),
                Map.of("issues", issuePages, "articles", data.articles().size())));

        long postdated = data.articles().stream().filter(MetricsService::datesOutOfOrder).count();
        metrics.put("postdated_count", new MetricValue((double) postdated, Map.of()));

        List<Calculations.VolumeAnomaly> anomalies = Calculations.volumeAnomalies(volumeByYear,
                rubric.threshold("volumeSpikeFactor"), rubric.threshold("volumeCollapseFactor"),
                (long) rubric.threshold("volumeAnomalyMinBase"));
        metrics.put("volume_anomalies", new MetricValue((double) anomalies.size(),
                Map.of("anomalies", anomalies.stream().map(a -> Map.<String, Object>of(
                        "year", a.year(), "previous", a.previous(), "current", a.current(),
                        "type", a.type())).toList())));

        // ---- diversity (FR-ANL-3)
        List<AuthorSlot> allSlots = data.authorsByArticle().values().stream()
                .flatMap(List::stream).toList();
        Map<String, Long> countryCounts = new HashMap<>();
        long slotsWithCountry = 0;
        long slotsMissingAffiliation = 0;
        for (AuthorSlot slot : allSlots) {
            if (slot.getCountry() != null) {
                countryCounts.merge(slot.getCountry(), 1L, Long::sum);
                slotsWithCountry++;
            }
            if (slot.getAffiliation() == null || slot.getAffiliation().isBlank()) {
                slotsMissingAffiliation++;
            }
        }
        metrics.put("author_country_hhi", new MetricValue(round(Calculations.hhi(countryCounts)),
                Map.of("distribution", Calculations.sortedByCountDesc(countryCounts),
                        "slotsWithCountry", slotsWithCountry, "slotsTotal", allSlots.size())));

        long singleAuthor = 0;
        long singleCountry = 0;
        long publisherAffiliated = 0;
        long articlesWithAuthors = 0;
        for (Article article : data.articles()) {
            List<AuthorSlot> slots = data.authorsByArticle().getOrDefault(article.getId(), List.of());
            if (slots.isEmpty()) {
                continue;
            }
            articlesWithAuthors++;
            if (slots.size() == 1) {
                singleAuthor++;
            }
            List<String> countries = slots.stream().map(AuthorSlot::getCountry)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            if (countries.size() == 1 && slots.stream().allMatch(s -> s.getCountry() != null)) {
                singleCountry++;
            }
            String publisher = data.journal().getPublisher();
            if (publisher != null && slots.stream().anyMatch(s ->
                    s.getAffiliation() != null && TextMatch.roughlyEqual(s.getAffiliation(), publisher))) {
                publisherAffiliated++;
            }
        }
        metrics.put("single_author_share", new MetricValue(
                round(Calculations.share(singleAuthor, articlesWithAuthors)),
                Map.of("singleAuthor", singleAuthor, "articles", articlesWithAuthors)));
        metrics.put("single_country_share", new MetricValue(
                round(Calculations.share(singleCountry, articlesWithAuthors)), Map.of()));
        metrics.put("publisher_institution_share", new MetricValue(
                round(Calculations.share(publisherAffiliated, articlesWithAuthors)), Map.of()));
        metrics.put("missing_affiliation_share", new MetricValue(
                round(Calculations.share(slotsMissingAffiliation, allSlots.size())),
                Map.of("missing", slotsMissingAffiliation, "slots", allSlots.size())));

        Map<String, Long> boardCountries = new HashMap<>();
        Map<String, Long> boardInstitutions = new HashMap<>();
        for (BoardMember member : data.board()) {
            if (member.getCountry() != null) {
                boardCountries.merge(member.getCountry(), 1L, Long::sum);
            }
            if (member.getInstitution() != null) {
                boardInstitutions.merge(member.getInstitution(), 1L, Long::sum);
            }
        }
        metrics.put("board_size", new MetricValue((double) data.board().size(), Map.of()));
        metrics.put("board_country_hhi", new MetricValue(round(Calculations.hhi(boardCountries)),
                Map.of("distribution", Calculations.sortedByCountDesc(boardCountries))));
        metrics.put("board_institution_top_share", new MetricValue(
                round(Calculations.topShare(boardInstitutions)),
                Map.of("distribution", Calculations.sortedByCountDesc(boardInstitutions))));

        // ---- citation standing (FR-ANL-4)
        metrics.put("citations_by_year", new MetricValue(null, Map.of("citedBy", data.citedByYear())));
        String trend = data.openAlexAvailable()
                ? Calculations.citationTrend(data.citedByYear(), currentYear) : "UNKNOWN";
        metrics.put("citation_trend", new MetricValue(null, Map.of("trend", trend)));
        if (data.twoYearMeanCitedness() != null) {
            metrics.put("two_year_mean_citedness",
                    new MetricValue(round(data.twoYearMeanCitedness()), Map.of()));
        }
        Calculations.SurgeCollapse surge = Calculations.surgeThenCollapse(data.citedByYear(),
                rubric.threshold("citationSurgeFactor"), rubric.threshold("citationCollapseFactor"));
        metrics.put("citation_surge_collapse", new MetricValue(surge == null ? 0.0 : 1.0,
                surge == null ? Map.of() : Map.of("peakYear", surge.peakYear(),
                        "peak", surge.peak(), "after", surge.after())));

        // Reference-based self-citation triad (deterministic approximation from the
        // extracted reference lists; citing-source analysis via OpenAlex is a later
        // deepening — absence of data is reported UNCLEAR, never as a good result).
        computeSelfCitation(metrics, data);

        // ---- availability inputs
        long pdfPages = data.snapshots().stream()
                .filter(s -> "article-pdf".equals(s.getPageType()) && s.getHttpStatus() == 200).count();
        long withDoi = data.articles().stream().filter(a -> a.getDoi() != null).count();
        long withAbstract = data.articles().stream()
                .filter(a -> a.getAbstractText() != null && !a.getAbstractText().isBlank()).count();
        long articleCount = data.articles().size();
        metrics.put("pdf_share", new MetricValue(round(Calculations.share(pdfPages, articleCount)),
                Map.of("pdfs", pdfPages, "articles", articleCount)));
        metrics.put("doi_share", new MetricValue(round(Calculations.share(withDoi, articleCount)), Map.of()));
        metrics.put("abstract_share", new MetricValue(
                round(Calculations.share(withAbstract, articleCount)), Map.of()));

        long englishOk = data.articles().stream().filter(a ->
                "ROMAN".equals(a.getTitleScript())
                        && ("en".equals(a.getAbstractLanguage()) || a.getAbstractText() == null)).count();
        metrics.put("english_share", new MetricValue(
                round(Calculations.share(englishOk, articleCount)), Map.of()));

        long allCapsTitles = data.articles().stream().filter(a -> a.getTitle() != null
                && a.getTitle().length() > 12
                && a.getTitle().equals(a.getTitle().toUpperCase(java.util.Locale.ROOT))).count();
        metrics.put("all_caps_title_share", new MetricValue(
                round(Calculations.share(allCapsTitles, articleCount)), Map.of()));
        long nonRomanNames = allSlots.stream().filter(s ->
                dev.hmcodes.jrap.extract.util.ScriptDetector.romanShare(s.getName()) < 0.5).count();
        metrics.put("non_roman_name_share", new MetricValue(
                round(Calculations.share(nonRomanNames, allSlots.size())), Map.of()));

        long withRefs = data.articles().stream().filter(a -> a.getReferencesCount() > 0).count();
        long romanRefLists = data.articles().stream().filter(a ->
                a.getReferencesRomanShare() != null
                        && a.getReferencesRomanShare().doubleValue() >= 0.9).count();
        metrics.put("roman_ref_list_share", new MetricValue(
                round(Calculations.share(romanRefLists, withRefs)),
                Map.of("romanLists", romanRefLists, "listsWithRefs", withRefs)));

        persist(audit, metrics);
        return metrics;
    }

    private void computeSelfCitation(Map<String, MetricValue> metrics, AnalysisData data) {
        String journalTitle = data.journal().getTitle();
        List<String> boardNames = data.board().stream().map(BoardMember::getNormalizedName).toList();
        long refListsTotal = 0;
        long journalSelf = 0;
        long ownAuthor = 0;
        long boardCited = 0;
        for (Article article : data.articles()) {
            if (article.getReferencesCount() == 0) {
                continue;
            }
            refListsTotal++;
            String refs = article.getReferencesJson().toLowerCase();
            if (journalTitle != null && refs.contains(journalTitle.toLowerCase())) {
                journalSelf++;
            }
            List<AuthorSlot> slots = data.authorsByArticle().getOrDefault(article.getId(), List.of());
            boolean own = slots.stream().anyMatch(s -> surnameAppears(refs, s.getNormalizedName()));
            if (own) {
                ownAuthor++;
            }
            if (boardNames.stream().anyMatch(n -> surnameAppears(refs, n))) {
                boardCited++;
            }
        }
        metrics.put("self_citation_journal_share", new MetricValue(
                round(Calculations.share(journalSelf, refListsTotal)),
                Map.of("lists", refListsTotal)));
        metrics.put("self_citation_author_share", new MetricValue(
                round(Calculations.share(ownAuthor, refListsTotal)), Map.of()));
        metrics.put("self_citation_board_share", new MetricValue(
                round(Calculations.share(boardCited, refListsTotal)), Map.of()));
    }

    private static boolean surnameAppears(String haystackLower, String normalizedName) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return false;
        }
        String[] tokens = normalizedName.split(" ");
        String surname = tokens[tokens.length - 1];
        return surname.length() >= 4 && haystackLower.contains(surname);
    }

    private static boolean datesOutOfOrder(Article article) {
        String submitted = article.getDateSubmitted();
        String accepted = article.getDateAccepted();
        String published = article.getDatePublished();
        return isoAfter(submitted, accepted) || isoAfter(accepted, published)
                || isoAfter(submitted, published);
    }

    /** True only for two clean ISO dates where a > b — displayed formats vary too much to guess. */
    private static boolean isoAfter(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (!a.matches("\\d{4}-\\d{2}-\\d{2}") || !b.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return false;
        }
        return a.compareTo(b) > 0;
    }

    private void persist(Audit audit, Map<String, MetricValue> metrics) {
        Instant now = clock.instant();
        List<AnalysisMetric> rows = new ArrayList<>();
        metrics.forEach((name, metric) -> rows.add(new AnalysisMetric(UUID.randomUUID(),
                audit.getOrganisationId(), audit.getId(), name,
                metric.value() == null ? null : BigDecimal.valueOf(metric.value()),
                toJson(metric.detail()), now)));
        tx.execute(status -> metricRepository.saveAll(rows));
    }

    private static Double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
