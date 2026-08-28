package dev.hmcodes.jrap.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.analysis.domain.GatewayCheck;
import dev.hmcodes.jrap.analysis.repo.GatewayCheckRepository;
import dev.hmcodes.jrap.analysis.rubric.Rubric;
import dev.hmcodes.jrap.analysis.service.MetricsService.MetricValue;
import dev.hmcodes.jrap.crawl.domain.Snapshot;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.hmcodes.jrap.analysis.domain.GatewayCheck.Outcome;

/**
 * FR-ANL-1 / §5.1: the six Scopus gateway checks, each PASS / PASS_WITH_CAVEATS / FAIL /
 * UNCLEAR with linked evidence. Unfetchable inputs become UNCLEAR, never silent gaps
 * (CON-2 spirit).
 */
@Service
public class GatewayCheckService {

    private final GatewayCheckRepository checks;
    private final EvidenceItemRepository evidenceItems;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public GatewayCheckService(GatewayCheckRepository checks, EvidenceItemRepository evidenceItems,
                               ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
                               Clock clock) {
        this.checks = checks;
        this.evidenceItems = evidenceItems;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public Map<String, Outcome> run(Audit audit, AnalysisData data, Map<String, MetricValue> metrics,
                                    Rubric rubric) {
        Map<String, Outcome> outcomes = new java.util.LinkedHashMap<>();
        outcomes.put("G1", pagePresenceCheck(audit, data, "G1", "peer-review-policy",
                "A public peer-review policy page"));
        outcomes.put("G2", regularityCheck(audit, data, metrics, rubric));
        outcomes.put("G3", issnCheck(audit, data));
        outcomes.put("G4", shareCheck(audit, data, metrics, rubric, "G4", "english_share",
                "englishShareMin", "englishShareCaveat",
                "English titles and abstracts on sampled articles"));
        outcomes.put("G5", pagePresenceCheck(audit, data, "G5", "ethics",
                "A public ethics / malpractice statement"));
        outcomes.put("G6", shareCheck(audit, data, metrics, rubric, "G6", "roman_ref_list_share",
                "romanRefListShareMin", "romanRefListShareCaveat",
                "Roman-script references in sampled reference lists"));
        return outcomes;
    }

    private Outcome pagePresenceCheck(Audit audit, AnalysisData data, String code, String pageType,
                                      String subject) {
        Optional<Snapshot> page = data.snapshots().stream()
                .filter(s -> pageType.equals(s.getPageType()) && s.getHttpStatus() == 200)
                .findFirst();
        if (page.isPresent()) {
            UUID evidence = snapshotEvidence(audit, page.get(),
                    subject + " was found at " + page.get().getUrl());
            save(audit, code, Outcome.PASS, subject + " is published on the site.", List.of(evidence));
            return Outcome.PASS;
        }
        if (data.snapshots().isEmpty()) {
            save(audit, code, Outcome.UNCLEAR, "The site could not be crawled, so " + subject
                    + " could not be verified.", List.of());
            return Outcome.UNCLEAR;
        }
        UUID evidence = computedEvidence(audit, code, subject + " was not found among the "
                + data.snapshots().size() + " crawled pages (page-type classification: none of type '"
                + pageType + "').");
        save(audit, code, Outcome.FAIL, subject + " was not found on the site.", List.of(evidence));
        return Outcome.FAIL;
    }

    private Outcome regularityCheck(Audit audit, AnalysisData data, Map<String, MetricValue> metrics,
                                    Rubric rubric) {
        MetricValue gaps = metrics.get("publication_gap_years");
        MetricValue avg = metrics.get("avg_articles_per_issue");
        MetricValue postdated = metrics.get("postdated_count");
        boolean noVolumeData = data.worksByYear().isEmpty() && data.articles().isEmpty();
        if (noVolumeData) {
            save(audit, "G2", Outcome.UNCLEAR,
                    "No publication-volume data is available from the site or OpenAlex.", List.of());
            return Outcome.UNCLEAR;
        }
        UUID evidence = computedEvidence(audit, "G2", "Publication years (crawl+OpenAlex): gaps="
                + gaps.detail() + ", avg articles/issue=" + avg.value()
                + ", date-order anomalies=" + postdated.value().intValue());
        if (gaps.value() > 0) {
            save(audit, "G2", Outcome.FAIL, "The trailing three full years include "
                    + gaps.value().intValue() + " year(s) with no publications: "
                    + gaps.detail().get("years") + ".", List.of(evidence));
            return Outcome.FAIL;
        }
        boolean thin = avg.value() > 0 && avg.value() < rubric.threshold("minAvgArticlesPerIssue");
        boolean hasPostdated = postdated.value() > 0;
        if (thin || hasPostdated) {
            save(audit, "G2", Outcome.PASS_WITH_CAVEATS, "Publication is continuous, with caveats: "
                    + (thin ? "thin issues (avg " + avg.value() + " articles/issue)" : "")
                    + (thin && hasPostdated ? "; " : "")
                    + (hasPostdated ? postdated.value().intValue() + " date-order anomalies" : "")
                    + ".", List.of(evidence));
            return Outcome.PASS_WITH_CAVEATS;
        }
        save(audit, "G2", Outcome.PASS,
                "No missed years in the trailing three; issues adequately filled.", List.of(evidence));
        return Outcome.PASS;
    }

    private Outcome issnCheck(Audit audit, AnalysisData data) {
        boolean conflicts = data.journalFindings().stream().anyMatch(f ->
                f.getCode().equals("IDENTITY_SWAPPED_ISSNS")
                        || f.getCode().equals("IDENTITY_ISSN_L_MISMATCH"));
        boolean registeredSomewhere = data.journal().getIssnL() != null;
        UUID evidence = computedEvidence(audit, "G3", "ISSN-L=" + data.journal().getIssnL()
                + ", print=" + data.journal().getIssnPrint() + ", online=" + data.journal().getIssnOnline()
                + ", identity conflicts=" + conflicts);
        if (!registeredSomewhere) {
            save(audit, "G3", Outcome.UNCLEAR,
                    "No source could confirm an ISSN registration.", List.of(evidence));
            return Outcome.UNCLEAR;
        }
        if (conflicts) {
            save(audit, "G3", Outcome.FAIL,
                    "The ISSN is registered but sources contradict each other (see identity findings).",
                    List.of(evidence));
            return Outcome.FAIL;
        }
        save(audit, "G3", Outcome.PASS_WITH_CAVEATS,
                "The ISSN is registered and consistent across reachable sources; the ISSN Portal "
                        + "itself could not be queried automatically.", List.of(evidence));
        return Outcome.PASS_WITH_CAVEATS;
    }

    private Outcome shareCheck(Audit audit, AnalysisData data, Map<String, MetricValue> metrics,
                               Rubric rubric, String code, String metricName, String minKey,
                               String caveatKey, String subject) {
        MetricValue metric = metrics.get(metricName);
        boolean noSample = data.articles().isEmpty()
                || (metricName.equals("roman_ref_list_share")
                        && ((Number) metric.detail().getOrDefault("listsWithRefs", 0)).longValue() == 0);
        if (noSample) {
            save(audit, code, Outcome.UNCLEAR, subject + " could not be sampled.", List.of());
            return Outcome.UNCLEAR;
        }
        double value = metric.value();
        UUID evidence = computedEvidence(audit, code,
                subject + ": share=" + value + " over detail=" + metric.detail());
        Outcome outcome;
        if (value >= rubric.threshold(minKey)) {
            outcome = Outcome.PASS;
        } else if (value >= rubric.threshold(caveatKey)) {
            outcome = Outcome.PASS_WITH_CAVEATS;
        } else {
            outcome = Outcome.FAIL;
        }
        save(audit, code, outcome, subject + ": " + Math.round(value * 100) + "%.", List.of(evidence));
        return outcome;
    }

    private UUID snapshotEvidence(Audit audit, Snapshot snapshot, String excerpt) {
        Instant now = clock.instant();
        EvidenceItem item = new EvidenceItem(UUID.randomUUID(), audit.getOrganisationId(),
                audit.getJournalId(), EvidenceItem.Type.SNAPSHOT, null, "SITE", excerpt,
                snapshot.getFetchedAt(), now);
        item.setSnapshotId(snapshot.getId());
        tx.execute(status -> evidenceItems.save(item));
        return item.getId();
    }

    private UUID computedEvidence(Audit audit, String code, String excerpt) {
        Instant now = clock.instant();
        EvidenceItem item = new EvidenceItem(UUID.randomUUID(), audit.getOrganisationId(),
                audit.getJournalId(), EvidenceItem.Type.COMPUTED, null, "ANALYSIS",
                "[" + code + "] " + excerpt, now, now);
        tx.execute(status -> evidenceItems.save(item));
        return item.getId();
    }

    private void save(Audit audit, String code, Outcome outcome, String summary, List<UUID> evidence) {
        Instant now = clock.instant();
        List<String> ids = new ArrayList<>();
        evidence.forEach(id -> ids.add(id.toString()));
        GatewayCheck check = new GatewayCheck(UUID.randomUUID(), audit.getOrganisationId(),
                audit.getId(), audit.getJournalId(), code, outcome, summary, toJson(ids), now);
        tx.execute(status -> checks.save(check));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
