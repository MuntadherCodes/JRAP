package dev.hmcodes.jrap.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.analysis.domain.CsabScore;
import dev.hmcodes.jrap.analysis.domain.GatewayCheck.Outcome;
import dev.hmcodes.jrap.analysis.repo.CsabScoreRepository;
import dev.hmcodes.jrap.analysis.rubric.Rubric;
import dev.hmcodes.jrap.analysis.service.MetricsService.MetricValue;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Finding;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-ANL-5 / §5.2: the five CSAB category scores, 0–5. Each category starts at 5 and
 * loses the rubric's configured delta per missed criterion; every criterion is recorded
 * met-or-missed so the report can cite exactly why (CON-5). Deterministic and LLM-free
 * by design (§3.1.6). Confirmed-only rule: unverified misconduct indicators never
 * subtract (CON-6) — they appear as criteria noted 'pending verification'.
 */
@Service
public class CsabScoringService {

    public record Criterion(String code, boolean met, int delta, String detail) {}

    private final CsabScoreRepository scores;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CsabScoringService(CsabScoreRepository scores, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager, Clock clock) {
        this.scores = scores;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public void score(Audit audit, AnalysisData data, Map<String, MetricValue> metrics,
                      Map<String, Outcome> gateway, List<Finding> auditFindings, Rubric rubric) {
        saveScore(audit, "policy", policy(data, metrics, gateway, auditFindings, rubric), null);
        saveScore(audit, "content", content(metrics, auditFindings, rubric), null);
        // §5.2 floor rule: a citation collapse floors journal standing at 1.
        String trend = String.valueOf(metrics.get("citation_trend").detail().get("trend"));
        boolean collapsed = "COLLAPSING".equals(trend)
                || metrics.get("citation_surge_collapse").value() > 0;
        saveScore(audit, "standing", standing(data, metrics, rubric), collapsed ? 1 : null);
        saveScore(audit, "regularity", regularity(metrics, rubric), null);
        saveScore(audit, "availability", availability(data, metrics, auditFindings, rubric), null);
    }

    private List<Criterion> policy(AnalysisData data, Map<String, MetricValue> metrics,
                                   Map<String, Outcome> gateway, List<Finding> auditFindings,
                                   Rubric rubric) {
        List<Criterion> criteria = new ArrayList<>();
        criteria.add(new Criterion("policy.reviewPolicyMissing",
                gateway.get("G1") == Outcome.PASS || gateway.get("G1") == Outcome.PASS_WITH_CAVEATS,
                rubric.delta("policy.reviewPolicyMissing"), "gateway G1=" + gateway.get("G1")));
        criteria.add(new Criterion("policy.ethicsMissing",
                gateway.get("G5") == Outcome.PASS || gateway.get("G5") == Outcome.PASS_WITH_CAVEATS,
                rubric.delta("policy.ethicsMissing"), "gateway G5=" + gateway.get("G5")));
        criteria.add(threshold(metrics, rubric, "policy.boardConcentration",
                "board_country_hhi", "boardCountryHhiMax", true));
        criteria.add(threshold(metrics, rubric, "policy.publisherInstitutionShare",
                "publisher_institution_share", "publisherInstitutionShareMax", true));
        boolean identityClean = data.journalFindings().stream()
                .noneMatch(f -> "identity".equals(f.getCategory())
                        && f.getSeverity().ordinal() <= Finding.Severity.MEDIUM.ordinal());
        criteria.add(new Criterion("policy.identityContradictions", identityClean,
                rubric.delta("policy.identityContradictions"),
                identityClean ? "no medium+ identity findings" : "identity findings present"));
        boolean noSolicitation = auditFindings.stream().noneMatch(f -> "RF-11".equals(f.getCode()));
        criteria.add(new Criterion("policy.citationSolicitation", noSolicitation,
                rubric.delta("policy.citationSolicitation"),
                noSolicitation ? "no solicitation indicators" : "RF-11 indicator pending verification"));
        return criteria;
    }

    private List<Criterion> content(Map<String, MetricValue> metrics, List<Finding> auditFindings,
                                    Rubric rubric) {
        List<Criterion> criteria = new ArrayList<>();
        criteria.add(threshold(metrics, rubric, "content.singleAuthorShare",
                "single_author_share", "singleAuthorShareMax", true));
        criteria.add(threshold(metrics, rubric, "content.missingAffiliations",
                "missing_affiliation_share", "missingAffiliationShareMax", true));
        criteria.add(threshold(metrics, rubric, "content.englishBelowBar",
                "english_share", "englishShareCaveat", false));
        criteria.add(threshold(metrics, rubric, "content.abstractsMissing",
                "abstract_share", "abstractShareMin", false));
        boolean confirmedIntegrity = auditFindings.stream().anyMatch(f ->
                ("RF-06".equals(f.getCode()) || "RF-07".equals(f.getCode()))
                        && f.getStatus() == Finding.Status.CONFIRMED);
        criteria.add(new Criterion("content.confirmedIntegrityIndicators", !confirmedIntegrity,
                rubric.delta("content.confirmedIntegrityIndicators"),
                confirmedIntegrity ? "confirmed copied-text/misattribution findings"
                        : "no CONFIRMED integrity findings (unverified indicators do not subtract)"));
        return criteria;
    }

    private List<Criterion> standing(AnalysisData data, Map<String, MetricValue> metrics, Rubric rubric) {
        List<Criterion> criteria = new ArrayList<>();
        if (!data.openAlexAvailable()) {
            criteria.add(new Criterion("standing.noCitationData", false,
                    rubric.delta("standing.noCitationData"),
                    "UNCLEAR — no citation source reachable; standing cannot be evidenced"));
            return criteria;
        }
        MetricValue citedness = metrics.get("two_year_mean_citedness");
        boolean citednessOk = citedness != null
                && citedness.value() >= rubric.threshold("twoYearCitednessLow");
        criteria.add(new Criterion("standing.lowCitedness", citednessOk,
                rubric.delta("standing.lowCitedness"),
                "2yr mean citedness=" + (citedness == null ? "n/a" : citedness.value())));
        double maxSelf = rubric.threshold("selfCitationShareMax");
        boolean selfOk = metrics.get("self_citation_journal_share").value() <= maxSelf
                && metrics.get("self_citation_board_share").value() <= maxSelf;
        criteria.add(new Criterion("standing.selfCitation", selfOk,
                rubric.delta("standing.selfCitation"), "self-citation triad vs "
                + Math.round(maxSelf * 100) + "% cap"));
        return criteria;
    }

    private List<Criterion> regularity(Map<String, MetricValue> metrics, Rubric rubric) {
        List<Criterion> criteria = new ArrayList<>();
        int gapYears = metrics.get("publication_gap_years").value().intValue();
        // §5.2: each missed year subtracts the gap delta (applied per year).
        criteria.add(new Criterion("regularity.gapYear", gapYears == 0,
                rubric.delta("regularity.gapYear") * Math.max(1, gapYears),
                "gap years=" + metrics.get("publication_gap_years").detail().get("years")));
        criteria.add(new Criterion("regularity.postdated",
                metrics.get("postdated_count").value() == 0,
                rubric.delta("regularity.postdated"),
                "date-order anomalies=" + metrics.get("postdated_count").value().intValue()));
        MetricValue avg = metrics.get("avg_articles_per_issue");
        boolean thick = avg.value() == 0 || avg.value() >= rubric.threshold("minAvgArticlesPerIssue");
        criteria.add(new Criterion("regularity.thinIssues", thick,
                rubric.delta("regularity.thinIssues"), "avg articles/issue=" + avg.value()));
        criteria.add(new Criterion("regularity.volumeAnomaly",
                metrics.get("volume_anomalies").value() == 0,
                rubric.delta("regularity.volumeAnomaly"),
                "anomalies=" + metrics.get("volume_anomalies").value().intValue()));
        return criteria;
    }

    private List<Criterion> availability(AnalysisData data, Map<String, MetricValue> metrics,
                                         List<Finding> auditFindings, Rubric rubric) {
        List<Criterion> criteria = new ArrayList<>();
        criteria.add(threshold(metrics, rubric, "availability.fullTextBelowBar",
                "pdf_share", "pdfShareMin", false));
        criteria.add(threshold(metrics, rubric, "availability.doiBelowBar",
                "doi_share", "doiShareMin", false));
        boolean identityConsistent = data.journalFindings().stream()
                .noneMatch(f -> "IDENTITY_SWAPPED_ISSNS".equals(f.getCode())
                        || "IDENTITY_ISSN_L_MISMATCH".equals(f.getCode()));
        criteria.add(new Criterion("availability.identityInconsistency", identityConsistent,
                rubric.delta("availability.identityInconsistency"),
                identityConsistent ? "identifiers consistent" : "conflicting identifiers"));
        boolean preserved = Boolean.TRUE.equals(data.doajPreservation());
        criteria.add(new Criterion("availability.noPreservation", preserved,
                rubric.delta("availability.noPreservation"),
                data.doajPreservation() == null ? "preservation arrangement unknown (DOAJ silent)"
                        : "DOAJ hasPreservation=" + data.doajPreservation()));
        boolean hygieneClean = auditFindings.stream().noneMatch(f -> "RF-13".equals(f.getCode()));
        criteria.add(new Criterion("availability.metadataHygiene", hygieneClean,
                rubric.delta("availability.metadataHygiene"),
                hygieneClean ? "no hygiene findings" : "RF-13 present"));
        return criteria;
    }

    private Criterion threshold(Map<String, MetricValue> metrics, Rubric rubric, String deltaKey,
                                String metricName, String thresholdKey, boolean maxIsBad) {
        MetricValue metric = metrics.get(metricName);
        double value = metric.value() == null ? 0 : metric.value();
        double bound = rubric.threshold(thresholdKey);
        boolean met = maxIsBad ? value <= bound : value >= bound;
        return new Criterion(deltaKey, met, rubric.delta(deltaKey),
                metricName + "=" + value + " vs " + (maxIsBad ? "max " : "min ") + bound);
    }

    private void saveScore(Audit audit, String category, List<Criterion> criteria, Integer cap) {
        int score = 5;
        for (Criterion criterion : criteria) {
            if (!criterion.met()) {
                score -= criterion.delta();
            }
        }
        if (cap != null) {
            score = Math.min(score, cap);
        }
        score = Math.max(0, Math.min(5, score));
        Instant now = clock.instant();
        CsabScore row = new CsabScore(UUID.randomUUID(), audit.getOrganisationId(), audit.getId(),
                audit.getJournalId(), category, score, toJson(criteria), now);
        tx.execute(status -> scores.save(row));
    }

    private String toJson(List<Criterion> criteria) {
        try {
            return objectMapper.writeValueAsString(criteria);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
