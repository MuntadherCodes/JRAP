package dev.hmcodes.jrap.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.analysis.repo.AnalysisMetricRepository;
import dev.hmcodes.jrap.analysis.repo.CsabScoreRepository;
import dev.hmcodes.jrap.analysis.repo.GatewayCheckRepository;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.platform.ActionItem;
import dev.hmcodes.jrap.registry.platform.ActionItemRepository;
import dev.hmcodes.jrap.registry.platform.AuditSchedule;
import dev.hmcodes.jrap.registry.platform.AuditScheduleRepository;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-DASH-1/4: the per-journal dashboard (score history, citation/volume trends from
 * stored metric details, diversity gauges, open actions) and the organisation
 * portfolio view with readiness trend.
 */
@Service
public class DashboardService {

    public record ScorePoint(UUID auditId, String finishedAt, Map<String, Integer> scores,
                             double mean) {}

    public record TrendSeries(Map<String, Long> byYear) {}

    public record JournalDashboard(UUID journalId, String title, List<ScorePoint> scoreHistory,
                                   List<GatewayRow> latestGateway, Map<String, BigDecimal> gauges,
                                   TrendSeries citationsByYear, TrendSeries articlesByYear,
                                   List<ActionItem> actions, AuditSchedule schedule) {}

    public record GatewayRow(String code, String outcome, String summary) {}

    public record PortfolioRow(UUID journalId, String title, String status, UUID latestAuditId,
                               String lastAuditAt, Double meanScore, Double previousMeanScore,
                               String trend, long gatewayFails, long openSevereFindings,
                               long openActions) {}

    private final JournalRepository journals;
    private final AuditRepository audits;
    private final CsabScoreRepository scores;
    private final GatewayCheckRepository gatewayChecks;
    private final AnalysisMetricRepository metrics;
    private final FindingRepository findings;
    private final ActionItemRepository actions;
    private final AuditScheduleRepository schedules;
    private final ObjectMapper objectMapper;

    public DashboardService(JournalRepository journals, AuditRepository audits,
                            CsabScoreRepository scores, GatewayCheckRepository gatewayChecks,
                            AnalysisMetricRepository metrics, FindingRepository findings,
                            ActionItemRepository actions, AuditScheduleRepository schedules,
                            ObjectMapper objectMapper) {
        this.journals = journals;
        this.audits = audits;
        this.scores = scores;
        this.gatewayChecks = gatewayChecks;
        this.metrics = metrics;
        this.findings = findings;
        this.actions = actions;
        this.schedules = schedules;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public JournalDashboard journalDashboard(Journal journal) {
        List<Audit> completed = audits.findByJournalIdOrderByCreatedAtDesc(journal.getId()).stream()
                .filter(a -> a.getStatus() == Audit.Status.COMPLETE)
                .toList();

        List<ScorePoint> history = new ArrayList<>();
        for (int i = completed.size() - 1; i >= 0; i--) {
            Audit audit = completed.get(i);
            Map<String, Integer> scoreMap = new LinkedHashMap<>();
            scores.findByAuditIdOrderByCategory(audit.getId())
                    .forEach(s -> scoreMap.put(s.getCategory(), s.getScore()));
            if (!scoreMap.isEmpty()) {
                double mean = scoreMap.values().stream().mapToInt(Integer::intValue)
                        .average().orElse(0);
                history.add(new ScorePoint(audit.getId(), String.valueOf(audit.getFinishedAt()),
                        scoreMap, Math.round(mean * 100) / 100.0));
            }
        }

        Audit latest = completed.isEmpty() ? null : completed.get(0);
        List<GatewayRow> gateway = latest == null ? List.of()
                : gatewayChecks.findByAuditIdOrderByCode(latest.getId()).stream()
                        .map(g -> new GatewayRow(g.getCode(), g.getOutcome(), g.getSummary()))
                        .toList();

        Map<String, BigDecimal> gauges = new LinkedHashMap<>();
        TrendSeries citations = new TrendSeries(Map.of());
        TrendSeries articles = new TrendSeries(Map.of());
        if (latest != null) {
            for (var metric : metrics.findByAuditIdOrderByName(latest.getId())) {
                switch (metric.getName()) {
                    case "author_country_hhi", "board_country_hhi", "single_country_share",
                         "citation_trend", "two_year_mean_citedness" ->
                            gauges.put(metric.getName(), metric.getValue());
                    case "citations_by_year" -> citations = series(metric.getDetail());
                    case "articles_by_year" -> articles = series(metric.getDetail());
                    default -> { }
                }
            }
        }

        return new JournalDashboard(journal.getId(), journal.getTitle(), history, gateway, gauges,
                citations, articles, actions.findByJournalIdOrderByCreatedAt(journal.getId()),
                schedules.findByJournalId(journal.getId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<PortfolioRow> portfolio() {
        List<PortfolioRow> rows = new ArrayList<>();
        for (Journal journal : journals.findAll()) {
            if (journal.getStatus() != Journal.Status.ACTIVE) {
                continue;
            }
            List<Audit> completed = audits.findByJournalIdOrderByCreatedAtDesc(journal.getId())
                    .stream().filter(a -> a.getStatus() == Audit.Status.COMPLETE).toList();
            Audit latest = completed.isEmpty() ? null : completed.get(0);
            Double mean = latest == null ? null : meanScore(latest.getId());
            Double previousMean = completed.size() < 2 ? null : meanScore(completed.get(1).getId());
            String trend = mean == null || previousMean == null ? "—"
                    : mean > previousMean ? "up" : mean < previousMean ? "down" : "flat";
            long fails = latest == null ? 0
                    : gatewayChecks.findByAuditIdOrderByCode(latest.getId()).stream()
                            .filter(g -> "FAIL".equals(g.getOutcome())).count();
            long severe = findings.findByJournalId(journal.getId()).stream()
                    .filter(f -> !f.isExcluded() && f.getStatus() != Finding.Status.REJECTED)
                    .filter(f -> f.getSeverity() == Finding.Severity.CRITICAL
                            || f.getSeverity() == Finding.Severity.HIGH)
                    .count();
            rows.add(new PortfolioRow(journal.getId(), journal.getTitle(),
                    journal.getStatus().name(), latest == null ? null : latest.getId(),
                    latest == null ? null : String.valueOf(latest.getFinishedAt()), mean,
                    previousMean, trend, fails,
                    severe, actions.countByJournalIdAndStatusNot(journal.getId(),
                            ActionItem.Status.DONE)));
        }
        return rows;
    }

    private Double meanScore(UUID auditId) {
        var rows = scores.findByAuditIdOrderByCategory(auditId);
        if (rows.isEmpty()) {
            return null;
        }
        return Math.round(rows.stream().mapToInt(s -> s.getScore()).average().orElse(0) * 100) / 100.0;
    }

    private TrendSeries series(String detailJson) {
        Map<String, Long> byYear = new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(detailJson == null ? "{}" : detailJson);
            // stored shapes: {"openalex": {year: n}, "crawl": {...}} (articles_by_year),
            // {"citedBy": {year: n}} (citations_by_year), {"distribution": {...}}, or flat.
            JsonNode map = node;
            for (String key : new String[]{"openalex", "citedBy", "distribution"}) {
                if (node.has(key) && node.get(key).size() > 0) {
                    map = node.get(key);
                    break;
                }
            }
            if (map == node && node.has("crawl")) {
                map = node.get("crawl");
            }
            map.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNumber() || entry.getValue().asText().matches("\\d+")) {
                    byYear.put(entry.getKey(), entry.getValue().asLong());
                }
            });
        } catch (Exception ignored) {
            // no chartable data
        }
        return new TrendSeries(byYear);
    }
}
