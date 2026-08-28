package dev.hmcodes.jrap.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.analysis.rubric.Rubric;
import dev.hmcodes.jrap.analysis.search.SearchProvider;
import dev.hmcodes.jrap.analysis.service.MetricsService.MetricValue;
import dev.hmcodes.jrap.crawl.domain.Snapshot;
import dev.hmcodes.jrap.crawl.store.SnapshotStore;
import dev.hmcodes.jrap.extract.domain.Article;
import dev.hmcodes.jrap.extract.domain.AuthorSlot;
import dev.hmcodes.jrap.extract.domain.BoardMember;
import dev.hmcodes.jrap.integrations.dto.SourceAvailability;
import dev.hmcodes.jrap.integrations.dto.SourceResult;
import dev.hmcodes.jrap.integrations.source.OpenAlexAdapter;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.EvidenceLink;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The red-flag catalogue (§5.3, FR-ANL-6..11). Deterministic detectors; misconduct-class
 * results (RF-06/07, RF-10/11) are ALWAYS "indicators requiring verification" — status
 * NEEDS_VERIFICATION, never assertions of guilt (CON-6) — and cannot enter a released
 * report until a human confirms them (FR-REV-3, Phase 6).
 */
@Service
public class RedFlagService {

    private static final Logger log = LoggerFactory.getLogger(RedFlagService.class);
    public static final String DETECTOR_VERSION = "red-flags/1.1.0";
    public static final String CATEGORY = "red-flag";

    /**
     * RF-12: top-level discipline terms counted (word-boundary) on the scope/about page.
     * A journal claiming many unrelated fields while publishing a thin annual volume is
     * the "scope over-breadth relative to output volume" pattern (§5.3).
     */
    private static final List<String> DISCIPLINE_TERMS = List.of(
            "medicine", "medical", "engineering", "physics", "chemistry", "biology",
            "mathematics", "economics", "law", "education", "agriculture",
            "computer science", "social science", "humanities", "arts", "nursing",
            "pharmacy", "veterinary", "geology", "linguistics", "psychology",
            "management", "environment", "energy", "materials science", "history",
            "sports", "architecture", "dentistry", "theology");

    private static final List<String> BREADTH_PHRASES = List.of(
            "multidisciplinary", "all fields", "all areas", "various fields",
            "all disciplines", "wide range of disciplines");

    private static final List<String> SOLICITATION_PATTERNS = List.of(
            "must cite", "required to cite", "cite at least", "citation requirement",
            "mandatory citation", "citing our journal", "الاستشهاد بمقالات المجلة");
    private static final List<String> INDEX_CLAIM_TERMS = List.of(
            "scopus", "web of science", "clarivate");

    private final FindingRepository findings;
    private final EvidenceItemRepository evidenceItems;
    private final EvidenceLinkRepository evidenceLinks;
    private final SnapshotStore snapshotStore;
    private final OpenAlexAdapter openAlex;
    private final SearchProvider searchProvider;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int misattributionSample;
    private final int copiedTextSample;

    public RedFlagService(FindingRepository findings, EvidenceItemRepository evidenceItems,
                          EvidenceLinkRepository evidenceLinks, SnapshotStore snapshotStore,
                          OpenAlexAdapter openAlex, SearchProvider searchProvider,
                          ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                          Clock clock,
                          @Value("${jrap.analysis.misattribution-sample:10}") int misattributionSample,
                          @Value("${jrap.analysis.copied-text-sample:10}") int copiedTextSample) {
        this.findings = findings;
        this.evidenceItems = evidenceItems;
        this.evidenceLinks = evidenceLinks;
        this.snapshotStore = snapshotStore;
        this.openAlex = openAlex;
        this.searchProvider = searchProvider;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.misattributionSample = misattributionSample;
        this.copiedTextSample = copiedTextSample;
    }

    public void run(Audit audit, AnalysisData data, Map<String, MetricValue> metrics, Rubric rubric) {
        volumeAnomalies(audit, metrics);                              // RF-01
        citationSurgeCollapse(audit, metrics);                        // RF-02
        selfCitation(audit, metrics, rubric);                         // RF-03
        boardSelfPublishing(audit, data);                             // RF-04 (+ same-day RF-05 facet)
        dateOrderAnomalies(audit, data);                              // RF-05
        misattributionIndicators(audit, data);                        // RF-06 (indicator, CON-6)
        copiedTextCandidates(audit, data);                            // RF-07 (indicator, CON-6)
        referenceInjection(audit, data, rubric);                      // RF-08
        indexClaims(audit, data);                                     // RF-10 (indicator)
        citationSolicitation(audit, data);                            // RF-11 (indicator)
        scopeOverBreadth(audit, data, rubric);                        // RF-12 (indicator)
        metadataHygiene(audit, metrics, rubric);                      // RF-13
        // RF-09 identity inconsistencies already exist as Phase-2 'identity' findings.
    }

    /**
     * RF-12 — scope over-breadth relative to output volume (§5.3): the journal's
     * scope/about page claims many unrelated top-level fields while the annual output
     * (OpenAlex works per year) is thin. Scope judgment is human territory, so this is
     * an indicator requiring verification, never an assertion.
     */
    private void scopeOverBreadth(Audit audit, AnalysisData data, Rubric rubric) {
        Snapshot scopePage = data.snapshots().stream()
                .filter(s -> "focus-and-scope".equals(s.getPageType()))
                .findFirst()
                .or(() -> data.snapshots().stream()
                        .filter(s -> "about".equals(s.getPageType())).findFirst())
                .or(() -> data.snapshots().stream()
                        .filter(s -> "home".equals(s.getPageType())).findFirst())
                .orElse(null);
        if (scopePage == null) {
            return;
        }
        String text = loadText(scopePage);
        if (text == null || text.isBlank()) {
            return;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        List<String> matched = DISCIPLINE_TERMS.stream()
                .filter(term -> Pattern.compile("\\b" + Pattern.quote(term)).matcher(lower).find())
                .toList();
        boolean phraseSignal = BREADTH_PHRASES.stream().anyMatch(lower::contains);
        int minFields = (int) rubric.threshold("scopeBreadthMinFields");
        if (matched.size() < minFields && !phraseSignal) {
            return;
        }

        // Annual output: mean works over the last three complete years OpenAlex knows.
        SortedMap<Integer, Long> byYear = data.worksByYear();
        if (byYear == null || byYear.isEmpty()) {
            return; // no volume evidence — regularity checks handle missing output data
        }
        List<Long> lastYears = byYear.keySet().stream()
                .sorted(java.util.Comparator.reverseOrder())
                .limit(3)
                .map(byYear::get)
                .toList();
        double annual = lastYears.stream().mapToLong(Long::longValue).average().orElse(0);
        if (annual >= rubric.threshold("scopeBreadthMaxAnnualOutput")) {
            return;
        }

        String breadth = phraseSignal
                ? "an explicitly unlimited scope (\"multidisciplinary\"/\"all fields\")"
                : matched.size() + " distinct top-level fields (" + String.join(", ", matched) + ")";
        record(audit, "RF-12", Finding.Severity.MEDIUM, Finding.Status.NEEDS_VERIFICATION,
                "Scope over-breadth relative to output volume",
                "The scope page claims " + breadth + " while the journal publishes about "
                        + Math.round(annual) + " articles/year. Broad claimed scope with thin "
                        + "output is a questionable-journal pattern; a reviewer should judge "
                        + "whether the stated scope is credible for this output level.",
                snapshotEvidence(audit, scopePage, clip(breadth + "; ~" + Math.round(annual) + "/year")));
    }

    private void volumeAnomalies(Audit audit, Map<String, MetricValue> metrics) {
        MetricValue metric = metrics.get("volume_anomalies");
        if (metric.value() == 0) {
            return;
        }
        record(audit, "RF-01", Finding.Severity.MEDIUM, Finding.Status.AUTO,
                "Year-over-year article volume anomaly",
                "Publication volume shows spike/collapse anomalies: " + metric.detail().get("anomalies")
                        + " (thresholds: >2x spike, <0.5x collapse).",
                computed(audit, "RF-01", "Volume by year and anomalies: " + metric.detail()));
    }

    private void citationSurgeCollapse(Audit audit, Map<String, MetricValue> metrics) {
        MetricValue metric = metrics.get("citation_surge_collapse");
        if (metric.value() == 0) {
            return;
        }
        record(audit, "RF-02", Finding.Severity.HIGH, Finding.Status.AUTO,
                "Citation surge followed by collapse",
                "Citations received peaked at " + metric.detail().get("peak") + " in year "
                        + metric.detail().get("peakYear") + " and later fell to "
                        + metric.detail().get("after")
                        + " — a surge-then-collapse pattern that CSAB reviewers treat as a warning sign.",
                computed(audit, "RF-02", "OpenAlex citations by year: "
                        + metrics.get("citations_by_year").detail()));
    }

    private void selfCitation(Audit audit, Map<String, MetricValue> metrics, Rubric rubric) {
        double max = rubric.threshold("selfCitationShareMax");
        Map<String, Double> shares = new LinkedHashMap<>();
        shares.put("journal", metrics.get("self_citation_journal_share").value());
        shares.put("own-author", metrics.get("self_citation_author_share").value());
        shares.put("board-member", metrics.get("self_citation_board_share").value());
        List<String> over = shares.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > max)
                .map(e -> e.getKey() + "=" + Math.round(e.getValue() * 100) + "%")
                .toList();
        if (over.isEmpty()) {
            return;
        }
        record(audit, "RF-03", Finding.Severity.MEDIUM, Finding.Status.AUTO,
                "Elevated self-citation in reference lists",
                "Share of reference lists citing the journal itself, its own authors, or its board "
                        + "members exceeds " + Math.round(max * 100) + "%: " + String.join(", ", over)
                        + " (computed from extracted reference lists).",
                computed(audit, "RF-03", "Self-citation shares: " + shares));
    }

    private void boardSelfPublishing(Audit audit, AnalysisData data) {
        Map<String, BoardMember> boardByName = new HashMap<>();
        for (BoardMember member : data.board()) {
            boardByName.putIfAbsent(member.getNormalizedName(), member);
        }
        Map<String, List<Article>> perMember = new LinkedHashMap<>();
        List<String> sameDay = new ArrayList<>();
        for (Article article : data.articles()) {
            for (AuthorSlot slot : data.authorsByArticle().getOrDefault(article.getId(), List.of())) {
                BoardMember member = boardByName.get(slot.getNormalizedName());
                if (member != null) {
                    perMember.computeIfAbsent(member.getName(), k -> new ArrayList<>()).add(article);
                    if (article.getDateSubmitted() != null
                            && article.getDateSubmitted().equals(article.getDateAccepted())) {
                        sameDay.add(member.getName() + " -> " + article.getTitle());
                    }
                }
            }
        }
        if (perMember.isEmpty()) {
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        perMember.forEach((name, articles) -> counts.put(name, articles.size()));
        record(audit, "RF-04", Finding.Severity.MEDIUM, Finding.Status.AUTO,
                "Editorial board members publish in their own journal",
                "Board-member authorship detected via normalised name matching: " + counts
                        + (sameDay.isEmpty() ? "" : ". Same-day submit/accept cases: " + sameDay)
                        + ". Volume and review-independence should be assessed by the analyst.",
                computed(audit, "RF-04", "Per-member article matches: " + counts
                        + "; same-day cases: " + sameDay));
    }

    private void dateOrderAnomalies(Audit audit, AnalysisData data) {
        List<String> anomalies = new ArrayList<>();
        for (Article article : data.articles()) {
            String s = article.getDateSubmitted();
            String a = article.getDateAccepted();
            String p = article.getDatePublished();
            if (cleanIsoAfter(s, a)) {
                anomalies.add("submitted after accepted: " + article.getTitle()
                        + " (" + s + " > " + a + ")");
            } else if (cleanIsoAfter(a, p)) {
                anomalies.add("accepted after published: " + article.getTitle()
                        + " (" + a + " > " + p + ")");
            }
        }
        if (anomalies.isEmpty()) {
            return;
        }
        record(audit, "RF-05", Finding.Severity.HIGH, Finding.Status.AUTO,
                "Backdated or out-of-order article dates",
                "Displayed workflow dates are out of order for " + anomalies.size()
                        + " article(s): " + String.join("; ", anomalies.subList(0,
                        Math.min(5, anomalies.size()))) + ".",
                computed(audit, "RF-05", String.join("\n", anomalies)));
    }

    private void misattributionIndicators(Audit audit, AnalysisData data) {
        int checked = 0;
        for (Article article : data.articles()) {
            if (checked >= misattributionSample) {
                break;
            }
            List<AuthorSlot> slots = data.authorsByArticle().getOrDefault(article.getId(), List.of());
            if (slots.size() != 1) {
                continue; // FR-ANL-8 targets sole-authored articles
            }
            AuthorSlot author = slots.get(0);
            checked++;
            SourceResult<OpenAlexAdapter.AuthorRecord> result = openAlex.searchAuthor(author.getName());
            if (result.availability() != SourceAvailability.OK || result.data() == null) {
                continue;
            }
            OpenAlexAdapter.AuthorRecord canonical = result.data();
            boolean nameMatches = canonical.displayName() != null
                    && canonical.displayName().equalsIgnoreCase(author.getName());
            boolean affiliationDiverges = canonical.institution() != null
                    && author.getAffiliation() != null
                    && !dev.hmcodes.jrap.extract.util.TextMatch.roughlyEqual(
                            author.getAffiliation(), canonical.institution());
            if (nameMatches && affiliationDiverges && canonical.worksCount() >= 20) {
                record(audit, "RF-06", Finding.Severity.HIGH, Finding.Status.NEEDS_VERIFICATION,
                        "Misattributed-authorship INDICATOR requiring verification",
                        "Sole author \"" + author.getName() + "\" (displayed affiliation: \""
                                + author.getAffiliation() + "\") matches a known researcher whose "
                                + "canonical affiliation is \"" + canonical.institution() + "\" ("
                                + canonical.worksCount() + " works in OpenAlex). This is an indicator "
                                + "requiring verification, not a determination"
                                + (searchProvider.isEnabled() ? "."
                                        : "; web-search corroboration was unavailable."),
                        computed(audit, "RF-06", "Article: " + article.getTitle() + " | site author: "
                                + author.getName() + " @ " + author.getAffiliation()
                                + " | OpenAlex: " + canonical.displayName() + " @ "
                                + canonical.institution()));
            }
        }
    }

    private void copiedTextCandidates(Audit audit, AnalysisData data) {
        if (!searchProvider.isEnabled()) {
            record(audit, "RF-07", Finding.Severity.INFO, Finding.Status.AUTO,
                    "UNCLEAR — copied-text screening unavailable",
                    "No web-search provider is configured, so exact-phrase screening of sampled "
                            + "abstracts (FR-ANL-9) could not run. Configure jrap.search.provider "
                            + "to enable it.",
                    computed(audit, "RF-07", "search provider disabled"));
            return;
        }
        int checked = 0;
        for (Article article : data.articles()) {
            if (checked >= copiedTextSample || article.getAbstractText() == null) {
                continue;
            }
            String phrase = exactPhrase(article.getAbstractText());
            if (phrase == null) {
                continue;
            }
            checked++;
            for (SearchProvider.SearchHit hit : searchProvider.search("\"" + phrase + "\"", 3)) {
                if (hit.snippet() != null && hit.snippet().toLowerCase(Locale.ROOT)
                        .contains(phrase.toLowerCase(Locale.ROOT))) {
                    record(audit, "RF-07", Finding.Severity.HIGH, Finding.Status.NEEDS_VERIFICATION,
                            "Copied-text CANDIDATE requiring verification",
                            "A sampled phrase from \"" + article.getTitle() + "\" appears verbatim in "
                                    + hit.url() + " (\"" + hit.title() + "\"). This is a candidate "
                                    + "requiring verification of direction and licence, not a "
                                    + "determination of plagiarism.",
                            computed(audit, "RF-07", "Phrase: \"" + phrase + "\" | Match: "
                                    + hit.url() + " | Snippet: " + hit.snippet()));
                    break;
                }
            }
        }
    }

    private void referenceInjection(Audit audit, AnalysisData data, Rubric rubric) {
        long listsTotal = data.articles().stream().filter(a -> a.getReferencesCount() > 0).count();
        long minLists = (long) rubric.threshold("refInjectionMinLists");
        if (listsTotal < minLists) {
            return;
        }
        // Surnames of frequent reference targets across unrelated articles.
        Map<String, Long> surnameLists = new HashMap<>();
        for (Article article : data.articles()) {
            if (article.getReferencesCount() == 0) {
                continue;
            }
            String refs = article.getReferencesJson().toLowerCase(Locale.ROOT);
            java.util.Set<String> seen = new java.util.HashSet<>();
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\\b\\p{L}{5,}\\b").matcher(refs);
            while (matcher.find()) {
                seen.add(matcher.group());
            }
            seen.forEach(word -> surnameLists.merge(word, 1L, Long::sum));
        }
        double shareThreshold = rubric.threshold("refInjectionListShare");
        List<String> suspicious = surnameLists.entrySet().stream()
                .filter(e -> e.getValue() >= minLists
                        && (double) e.getValue() / listsTotal >= Math.max(shareThreshold, 0.5)
                        && !COMMON_WORDS.contains(e.getKey()))
                .map(e -> e.getKey() + " (" + e.getValue() + "/" + listsTotal + " lists)")
                .limit(5)
                .toList();
        if (suspicious.isEmpty()) {
            return;
        }
        record(audit, "RF-08", Finding.Severity.LOW, Finding.Status.AUTO,
                "Possible reference injection / citation stacking",
                "Names or terms recur across most reference lists: " + String.join(", ", suspicious)
                        + ". Frequency alone is not proof — topical fields legitimately share "
                        + "canonical references; analyst review advised.",
                computed(audit, "RF-08", "Recurring reference terms: " + suspicious));
    }

    private static final java.util.Set<String> COMMON_WORDS = java.util.Set.of(
            "journal", "university", "review", "science", "sciences", "research", "analysis",
            "study", "studies", "medical", "medicine", "clinical", "international", "engineering",
            "applied", "advances", "computing", "systems", "signal", "processing", "press");

    private void indexClaims(Audit audit, AnalysisData data) {
        for (Snapshot snapshot : data.snapshots()) {
            if (!"home".equals(snapshot.getPageType()) && !"indexing".equals(snapshot.getPageType())) {
                continue;
            }
            String text = loadText(snapshot);
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String term : INDEX_CLAIM_TERMS) {
                int at = lower.indexOf(term);
                if (at >= 0) {
                    String context = text.substring(Math.max(0, at - 80),
                            Math.min(text.length(), at + 80)).trim();
                    record(audit, "RF-10", Finding.Severity.MEDIUM, Finding.Status.NEEDS_VERIFICATION,
                            "Indexing claim requiring verification: \"" + term + "\"",
                            "The site mentions \"" + term + "\" on its " + snapshot.getPageType()
                                    + " page. Whether the journal is actually indexed there must be "
                                    + "verified by the analyst; unregistered index claims are a "
                                    + "known integrity red flag.",
                            snapshotEvidence(audit, snapshot, "…" + context + "…"));
                    break; // one finding per page
                }
            }
        }
    }

    private void citationSolicitation(Audit audit, AnalysisData data) {
        for (Snapshot snapshot : data.snapshots()) {
            if (!"announcements".equals(snapshot.getPageType())) {
                continue;
            }
            String text = loadText(snapshot);
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String pattern : SOLICITATION_PATTERNS) {
                int at = lower.indexOf(pattern);
                if (at >= 0) {
                    String context = text.substring(Math.max(0, at - 100),
                            Math.min(text.length(), at + 150)).trim();
                    record(audit, "RF-11", Finding.Severity.HIGH, Finding.Status.NEEDS_VERIFICATION,
                            "Citation-solicitation INDICATOR requiring verification",
                            "An announcement contains language resembling a citation requirement "
                                    + "(matched: \"" + pattern + "\"). The analyst must confirm the "
                                    + "announcement's intent before this enters any report.",
                            snapshotEvidence(audit, snapshot, "…" + context + "…"));
                    break;
                }
            }
        }
    }

    private void metadataHygiene(Audit audit, Map<String, MetricValue> metrics, Rubric rubric) {
        List<String> issues = new ArrayList<>();
        MetricValue missing = metrics.get("missing_affiliation_share");
        if (missing.value() > rubric.threshold("missingAffiliationShareMax")) {
            issues.add("missing author affiliations on " + Math.round(missing.value() * 100)
                    + "% of author slots");
        }
        MetricValue allCaps = metrics.get("all_caps_title_share");
        if (allCaps.value() > rubric.threshold("allCapsTitleShareMax")) {
            issues.add("ALL-CAPS titles on " + Math.round(allCaps.value() * 100) + "% of articles");
        }
        MetricValue nonRoman = metrics.get("non_roman_name_share");
        if (nonRoman.value() > rubric.threshold("nonRomanNameShareMax")) {
            issues.add("non-Roman author names on " + Math.round(nonRoman.value() * 100)
                    + "% of author slots (Scopus requires Roman-script metadata)");
        }
        if (issues.isEmpty()) {
            return;
        }
        record(audit, "RF-13", Finding.Severity.LOW, Finding.Status.AUTO,
                "Metadata hygiene issues",
                "Metadata quality problems that depress the online-availability assessment: "
                        + String.join("; ", issues) + ".",
                computed(audit, "RF-13", String.join("; ", issues)));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean cleanIsoAfter(String a, String b) {
        return a != null && b != null
                && a.matches("\\d{4}-\\d{2}-\\d{2}") && b.matches("\\d{4}-\\d{2}-\\d{2}")
                && a.compareTo(b) > 0;
    }

    private static String exactPhrase(String abstractText) {
        String[] words = abstractText.trim().split("\\s+");
        if (words.length < 12) {
            return null;
        }
        return String.join(" ", java.util.Arrays.copyOfRange(words, 2, 12));
    }

    private String loadText(Snapshot snapshot) {
        try {
            if (snapshot.getTextStorageKey() == null) {
                return null;
            }
            return new String(snapshotStore.get(snapshot.getTextStorageKey()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to load snapshot text {}: {}", snapshot.getId(), e.getMessage());
            return null;
        }
    }

    private UUID computed(Audit audit, String code, String excerpt) {
        Instant now = clock.instant();
        EvidenceItem item = new EvidenceItem(UUID.randomUUID(), audit.getOrganisationId(),
                audit.getJournalId(), EvidenceItem.Type.COMPUTED, null, "ANALYSIS",
                "[" + code + "] " + clip(excerpt), now, now);
        tx.execute(status -> evidenceItems.save(item));
        return item.getId();
    }

    private UUID snapshotEvidence(Audit audit, Snapshot snapshot, String excerpt) {
        Instant now = clock.instant();
        EvidenceItem item = new EvidenceItem(UUID.randomUUID(), audit.getOrganisationId(),
                audit.getJournalId(), EvidenceItem.Type.SNAPSHOT, null, "SITE", clip(excerpt),
                snapshot.getFetchedAt(), now);
        item.setSnapshotId(snapshot.getId());
        tx.execute(status -> evidenceItems.save(item));
        return item.getId();
    }

    private void record(Audit audit, String code, Finding.Severity severity, Finding.Status status,
                        String title, String description, UUID evidenceId) {
        Instant now = clock.instant();
        tx.execute(txStatus -> {
            Finding finding = new Finding(UUID.randomUUID(), audit.getOrganisationId(),
                    audit.getJournalId(), CATEGORY, code, severity, status, title, description,
                    DETECTOR_VERSION, now);
            finding.setAuditId(audit.getId());
            findings.save(finding);
            evidenceLinks.save(new EvidenceLink(finding.getId(), evidenceId,
                    audit.getOrganisationId()));
            return null;
        });
    }

    private static String clip(String text) {
        return text != null && text.length() > 2000 ? text.substring(0, 2000) : text;
    }
}
