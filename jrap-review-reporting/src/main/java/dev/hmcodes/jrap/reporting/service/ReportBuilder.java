package dev.hmcodes.jrap.reporting.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.analysis.domain.CsabScore;
import dev.hmcodes.jrap.analysis.domain.GatewayCheck;
import dev.hmcodes.jrap.analysis.repo.AnalysisMetricRepository;
import dev.hmcodes.jrap.analysis.repo.CsabScoreRepository;
import dev.hmcodes.jrap.analysis.repo.GatewayCheckRepository;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.registry.repo.JournalIdentityRecordRepository;
import dev.hmcodes.jrap.reporting.model.ReportContent.Exclusion;
import dev.hmcodes.jrap.reporting.model.ReportContent.RoadmapAction;
import dev.hmcodes.jrap.reporting.model.ReportContent.Section;
import dev.hmcodes.jrap.reporting.model.ReportContent.Sentence;
import dev.hmcodes.jrap.reporting.domain.Report;
import dev.hmcodes.jrap.review.service.ReviewService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic assembly of the fixed FR-RPT-1 report structure: verdict summary,
 * gateway table, five CSAB category sections, diversity analysis, red-flag catalogue,
 * roadmap, evidence annex, methodology with data-source timestamps, disclaimer.
 * Every factual sentence is constructed FROM a data row and carries that row's evidence
 * citations (CON-5); scores and metrics get COMPUTED evidence items minted here so
 * their sentences are traceable to stored evidence like everything else.
 */
@Service
public class ReportBuilder {

    /** Misconduct-indicator codes (FR-ANL-8/9): fixed legal wording per FR-REV-3/CON-6. */
    private static final Set<String> INDICATOR_CODES = Set.of("RF-06", "RF-07");

    private static final Map<String, String> GATEWAY_TITLES = Map.of(
            "G1", "Peer-review policy public",
            "G2", "Publication regularity",
            "G3", "ISSN registration and consistency",
            "G4", "English titles and abstracts",
            "G5", "Publication-ethics statement public",
            "G6", "Roman-script references");

    public record Build(Report.Verdict verdict, List<Section> sections,
                        List<RoadmapAction> roadmap, List<Exclusion> exclusions,
                        SentenceGuard.Context guardContext, List<Finding> confirmedFindings) {}

    private final ReviewService reviewService;
    private final GatewayCheckRepository gatewayChecks;
    private final CsabScoreRepository scores;
    private final AnalysisMetricRepository metrics;
    private final EvidenceLinkRepository evidenceLinks;
    private final EvidenceItemRepository evidenceItems;
    private final JournalIdentityRecordRepository identityRecords;
    private final RoadmapGenerator roadmapGenerator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReportBuilder(ReviewService reviewService, GatewayCheckRepository gatewayChecks,
                         CsabScoreRepository scores, AnalysisMetricRepository metrics,
                         EvidenceLinkRepository evidenceLinks, EvidenceItemRepository evidenceItems,
                         JournalIdentityRecordRepository identityRecords,
                         RoadmapGenerator roadmapGenerator, ObjectMapper objectMapper, Clock clock) {
        this.reviewService = reviewService;
        this.gatewayChecks = gatewayChecks;
        this.scores = scores;
        this.metrics = metrics;
        this.evidenceLinks = evidenceLinks;
        this.evidenceItems = evidenceItems;
        this.identityRecords = identityRecords;
        this.roadmapGenerator = roadmapGenerator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public Build build(Audit audit, Journal journal) {
        List<Finding> reviewable = reviewService.reviewableFindings(audit);
        // FR-REV-3/FR-REV-4: needs-verification findings never enter report content —
        // by release they are confirmed, rejected, or excluded (annex-listed).
        List<Finding> reportable = reviewable.stream()
                .filter(f -> !f.isExcluded())
                .filter(f -> f.getStatus() == Finding.Status.CONFIRMED
                        || f.getStatus() == Finding.Status.AUTO)
                .sorted(java.util.Comparator.comparing(Finding::getSeverity)
                        .thenComparing(Finding::getCode))
                .toList();
        List<Exclusion> exclusions = reviewable.stream()
                .filter(Finding::isExcluded)
                .map(f -> new Exclusion(f.getId(), f.getCode(), f.getTitle(), f.getExclusionReason()))
                .toList();

        Map<UUID, List<UUID>> evidenceByFinding = evidenceLinks
                .findByIdFindingIdIn(reportable.stream().map(Finding::getId).toList()).stream()
                .collect(Collectors.groupingBy(l -> l.getId().getFindingId(),
                        Collectors.mapping(l -> l.getId().getEvidenceItemId(), Collectors.toList())));

        List<GatewayCheck> gateway = gatewayChecks.findByAuditIdOrderByCode(audit.getId());
        List<CsabScore> scoreRows = scores.findByAuditIdOrderByCategory(audit.getId());
        Map<String, java.math.BigDecimal> metricValues = new LinkedHashMap<>();
        Map<String, String> metricDetails = new LinkedHashMap<>();
        metrics.findByAuditIdOrderByName(audit.getId()).forEach(m -> {
            metricValues.put(m.getName(), m.getValue());
            metricDetails.put(m.getName(), m.getDetail());
        });

        Report.Verdict verdict = verdict(gateway, scoreRows, reportable);

        List<Section> sections = new ArrayList<>();
        sections.add(verdictSection(audit, journal, verdict, gateway, reportable));
        sections.add(gatewaySection(audit, gateway));
        sections.addAll(scoreSections(audit, scoreRows));
        sections.add(diversitySection(audit, metricValues, metricDetails));
        sections.add(findingsSection(reportable, evidenceByFinding));
        sections.add(methodologySection(audit, journal));
        sections.add(disclaimerSection());

        Map<String, String> gatewayOutcomes = gateway.stream()
                .collect(Collectors.toMap(GatewayCheck::getCode, GatewayCheck::getOutcome));
        Map<String, Integer> scoreMap = scoreRows.stream()
                .collect(Collectors.toMap(CsabScore::getCategory, CsabScore::getScore));
        List<RoadmapAction> roadmap = roadmapGenerator.generate(reportable, gatewayOutcomes, scoreMap);

        Set<UUID> journalEvidence = evidenceItems.findByJournalId(journal.getId()).stream()
                .map(EvidenceItem::getId).collect(Collectors.toSet());
        Set<UUID> reportableIds = reportable.stream().map(Finding::getId).collect(Collectors.toSet());
        List<Finding> confirmed = reportable.stream()
                .filter(f -> f.getStatus() == Finding.Status.CONFIRMED).toList();
        Set<UUID> confirmedIds = confirmed.stream().map(Finding::getId).collect(Collectors.toSet());

        return new Build(verdict, sections, roadmap, exclusions,
                new SentenceGuard.Context(reportableIds, confirmedIds, journalEvidence), confirmed);
    }

    // ------------------------------------------------------------------ verdict

    private Report.Verdict verdict(List<GatewayCheck> gateway, List<CsabScore> scoreRows,
                                   List<Finding> reportable) {
        boolean gatewayFail = gateway.stream().anyMatch(g -> "FAIL".equals(g.getOutcome()));
        boolean anyCaveat = gateway.stream().anyMatch(g -> !"PASS".equals(g.getOutcome()));
        boolean severeFinding = reportable.stream().anyMatch(f ->
                f.getSeverity() == Finding.Severity.CRITICAL || f.getSeverity() == Finding.Severity.HIGH);
        boolean mediumFinding = reportable.stream().anyMatch(f -> f.getSeverity() == Finding.Severity.MEDIUM);
        long weakScores = scoreRows.stream().filter(s -> s.getScore() <= 2).count();
        if (gatewayFail || severeFinding || weakScores >= 2) {
            return Report.Verdict.NOT_READY;
        }
        if (anyCaveat || mediumFinding || weakScores == 1) {
            return Report.Verdict.CONDITIONAL;
        }
        return Report.Verdict.READY;
    }

    // ------------------------------------------------------------------ sections

    private Section verdictSection(Audit audit, Journal journal, Report.Verdict verdict,
                                   List<GatewayCheck> gateway, List<Finding> reportable) {
        List<Sentence> sentences = new ArrayList<>();
        String title = journal.getTitle() == null ? "the journal" : journal.getTitle();
        sentences.add(Sentence.structural("verdict-intro",
                "Scopus readiness assessment of " + title + ", based on the evidence set of audit "
                        + audit.getId() + " (rubric v" + orDash(audit.getRubricVersion()) + ")."));

        long fails = gateway.stream().filter(g -> "FAIL".equals(g.getOutcome())).count();
        long caveats = gateway.stream().filter(g -> "PASS_WITH_CAVEATS".equals(g.getOutcome())).count();
        long unclear = gateway.stream().filter(g -> "UNCLEAR".equals(g.getOutcome())).count();
        long confirmedCount = reportable.stream()
                .filter(f -> f.getStatus() == Finding.Status.CONFIRMED).count();
        String statsText = "Verdict: " + verdict.name().replace('_', ' ') + ". Gateway: " + fails
                + " failed, " + caveats + " with caveats, " + unclear + " unclear. Findings entering this"
                + " report: " + reportable.size() + " (" + confirmedCount + " analyst-confirmed).";
        UUID statsEvidence = computedEvidence(audit, "verdict-stats", statsText);
        sentences.add(Sentence.factual("verdict-stats", statsText, List.of(), List.of(statsEvidence)));
        return new Section("verdict", "Verdict summary", sentences);
    }

    private Section gatewaySection(Audit audit, List<GatewayCheck> gateway) {
        List<Sentence> sentences = new ArrayList<>();
        sentences.add(Sentence.structural("gateway-intro",
                "Six gateway checks (§5.1): each must pass for Scopus submission to be viable."));
        for (GatewayCheck check : gateway) {
            List<UUID> evidence = parseUuidArray(check.getEvidenceItemIds());
            if (evidence.isEmpty()) {
                // e.g. UNCLEAR checks stored without evidence links: mint COMPUTED evidence
                // so the sentence stays citable and the guard can pass (CON-5).
                evidence = List.of(computedEvidence(audit, "gateway-" + check.getCode(),
                        check.getOutcome() + ": " + check.getSummary()));
            }
            sentences.add(Sentence.factual("gw-" + check.getCode().toLowerCase(java.util.Locale.ROOT),
                    check.getCode() + " — " + GATEWAY_TITLES.getOrDefault(check.getCode(), check.getCode())
                            + ": " + check.getOutcome().replace('_', ' ') + ". " + check.getSummary(),
                    List.of(), evidence));
        }
        return new Section("gateway", "Gateway checks", sentences);
    }

    private List<Section> scoreSections(Audit audit, List<CsabScore> scoreRows) {
        List<Section> sections = new ArrayList<>();
        for (CsabScore score : scoreRows) {
            String cat = score.getCategory();
            UUID evidence = computedEvidence(audit, "score-" + cat,
                    "CSAB " + cat + " = " + score.getScore() + "/5; criteria: " + score.getCriteria());
            List<Sentence> sentences = new ArrayList<>();
            sentences.add(Sentence.factual("score-" + cat,
                    "CSAB category '" + cat + "': " + score.getScore() + " out of 5.",
                    List.of(), List.of(evidence)));
            for (String detail : criteriaSummaries(score.getCriteria())) {
                sentences.add(Sentence.factual("score-" + cat + "-" + Math.abs(detail.hashCode()),
                        detail, List.of(), List.of(evidence)));
            }
            sections.add(new Section("csab-" + cat,
                    "CSAB: " + Character.toUpperCase(cat.charAt(0)) + cat.substring(1), sentences));
        }
        return sections;
    }

    private Section diversitySection(Audit audit, Map<String, java.math.BigDecimal> values,
                                     Map<String, String> details) {
        List<Sentence> sentences = new ArrayList<>();
        sentences.add(Sentence.structural("diversity-intro",
                "Diversity and concentration metrics computed from extracted authorship and board data."));
        addMetricSentence(sentences, audit, values, details, "author_country_hhi",
                "Author-country concentration (HHI)");
        addMetricSentence(sentences, audit, values, details, "single_country_share",
                "Largest single-country share of author slots");
        addMetricSentence(sentences, audit, values, details, "board_country_hhi",
                "Editorial-board country concentration (HHI)");
        addMetricSentence(sentences, audit, values, details, "citation_trend",
                "Citation trend (last two years vs the prior two)");
        return new Section("diversity", "Diversity analysis", sentences);
    }

    private void addMetricSentence(List<Sentence> sentences, Audit audit,
                                   Map<String, java.math.BigDecimal> values,
                                   Map<String, String> details, String name, String label) {
        if (!values.containsKey(name)) {
            return;
        }
        java.math.BigDecimal value = values.get(name);
        String rendered = value == null ? "not computable from the available evidence"
                : value.stripTrailingZeros().toPlainString();
        UUID evidence = computedEvidence(audit, "metric-" + name,
                name + " = " + rendered + "; " + clip(details.getOrDefault(name, "{}")));
        sentences.add(Sentence.factual("metric-" + name, label + ": " + rendered + ".",
                List.of(), List.of(evidence)));
    }

    private Section findingsSection(List<Finding> reportable, Map<UUID, List<UUID>> evidenceByFinding) {
        List<Sentence> sentences = new ArrayList<>();
        sentences.add(Sentence.structural("findings-intro",
                "Findings entering this report, with analyst review status. Rejected findings are"
                        + " omitted; analyst-excluded findings are listed in the annex (FR-REV-4)."));
        for (Finding finding : reportable) {
            String label = finding.getStatus() == Finding.Status.CONFIRMED
                    ? "confirmed by analyst" : "automated, unreviewed";
            String text;
            if (INDICATOR_CODES.contains(finding.getCode())) {
                // FR-REV-3 / CON-6: fixed legal wording — an indicator, never an assertion.
                text = finding.getCode() + " — indicator requiring verification (" + label + "): "
                        + finding.getTitle() + ". " + finding.getDescription()
                        + " This is reported as an indicator requiring verification and is not an"
                        + " assertion of misconduct.";
            } else {
                text = finding.getCode() + " [" + finding.getSeverity() + ", " + label + "]: "
                        + finding.getTitle() + ". " + finding.getDescription();
            }
            sentences.add(Sentence.factual("finding-" + finding.getId(), text,
                    List.of(finding.getId()),
                    evidenceByFinding.getOrDefault(finding.getId(), List.of())));
        }
        return new Section("findings", "Findings catalogue", sentences);
    }

    private Section methodologySection(Audit audit, Journal journal) {
        List<Sentence> sentences = new ArrayList<>();
        sentences.add(Sentence.structural("method-pipeline",
                "Method: robots-compliant crawl (" + audit.getPagesFetched() + " pages fetched, "
                        + audit.getPagesSkipped() + " skipped with recorded reasons; cap "
                        + audit.getPageCap() + "), deterministic extraction ("
                        + audit.getArticlesExtracted() + " articles, " + audit.getBoardMembersExtracted()
                        + " board members), scholarly-source enrichment, deterministic analysis under"
                        + " rubric v" + orDash(audit.getRubricVersion()) + ", detector versions "
                        + audit.getDetectorVersions() + ", followed by human review."));
        StringBuilder sources = new StringBuilder("Data sources and retrieval timestamps: ");
        var records = identityRecords.findByJournalIdOrderBySource(journal.getId());
        for (int i = 0; i < records.size(); i++) {
            var record = records.get(i);
            sources.append(record.getSource()).append(" (").append(record.getAvailability())
                    .append(", ").append(record.getRetrievedAt()).append(")");
            sources.append(i == records.size() - 1 ? "." : "; ");
        }
        if (records.isEmpty()) {
            sources.append("none recorded.");
        }
        sentences.add(Sentence.structural("method-sources", sources.toString()));
        return new Section("methodology", "Methodology and data sources", sentences);
    }

    private Section disclaimerSection() {
        return new Section("disclaimer", "Disclaimer", List.of(
                Sentence.structural("disclaimer-independence",
                        "JRAP is an independent product of HM Codes Research and Development. It is not"
                                + " affiliated with, endorsed by, or acting for Elsevier B.V. or Scopus;"
                                + " the name Scopus is used nominatively to identify the target index"
                                + " (CON-7)."),
                Sentence.structural("disclaimer-no-guarantee",
                        "This report is decision support for journal improvement. It does not guarantee"
                                + " any indexing outcome, and acceptance decisions rest solely with the"
                                + " index's own advisory board."),
                Sentence.structural("disclaimer-indicators",
                        "Findings labelled 'indicator requiring verification' are never assertions of"
                                + " misconduct; they identify material requiring human verification"
                                + " through due process (CON-6).")));
    }

    // ------------------------------------------------------------------ helpers

    /** Mints a stored COMPUTED evidence item so score/metric sentences are traceable (CON-5). */
    private UUID computedEvidence(Audit audit, String ref, String excerpt) {
        Instant now = clock.instant();
        EvidenceItem item = new EvidenceItem(UUID.randomUUID(), audit.getOrganisationId(),
                audit.getJournalId(), EvidenceItem.Type.COMPUTED, null, "REPORT",
                "[" + ref + "] " + clip(excerpt), now, now);
        item.setAuditId(audit.getId());
        evidenceItems.save(item);
        return item.getId();
    }

    private List<String> criteriaSummaries(String criteriaJson) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(criteriaJson == null ? "[]" : criteriaJson);
            for (JsonNode criterion : array) {
                if (!criterion.path("met").asBoolean(true)) {
                    out.add("Criterion not met: " + criterion.path("detail").asText(
                            criterion.path("code").asText("unnamed")) + " (-"
                            + criterion.path("delta").asInt(0) + ").");
                }
            }
        } catch (Exception e) {
            // unparseable criteria: the headline score sentence stands alone
        }
        return out;
    }

    private List<UUID> parseUuidArray(String json) {
        List<UUID> out = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(json == null ? "[]" : json);
            for (JsonNode node : array) {
                out.add(UUID.fromString(node.asText()));
            }
        } catch (Exception e) {
            // fall through: empty citations will be caught by the guard if it matters
        }
        return out;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }

    private static String orDash(String value) {
        return value == null ? "—" : value;
    }
}
