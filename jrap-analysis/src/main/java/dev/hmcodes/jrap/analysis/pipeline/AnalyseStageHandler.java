package dev.hmcodes.jrap.analysis.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.analysis.domain.GatewayCheck.Outcome;
import dev.hmcodes.jrap.analysis.repo.GatewayCheckRepository;
import dev.hmcodes.jrap.analysis.rubric.Rubric;
import dev.hmcodes.jrap.analysis.rubric.RubricLoader;
import dev.hmcodes.jrap.analysis.service.AnalysisData;
import dev.hmcodes.jrap.analysis.service.AnalysisDataLoader;
import dev.hmcodes.jrap.analysis.service.CsabScoringService;
import dev.hmcodes.jrap.analysis.service.GatewayCheckService;
import dev.hmcodes.jrap.analysis.service.MetricsService;
import dev.hmcodes.jrap.analysis.service.RedFlagService;
import dev.hmcodes.jrap.extract.service.ReconciliationService;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.pipeline.AuditStageHandler;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import dev.hmcodes.jrap.tenancy.service.TenantTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The ANALYSE stage (FR-ANL-1..12): freezes the rubric and detector versions on the
 * audit (§3.3), then runs metrics → gateway checks → red flags → CSAB scores.
 * Idempotent: an audit that already has gateway checks skips the whole stage.
 */
@Component
public class AnalyseStageHandler implements AuditStageHandler {

    private static final Logger log = LoggerFactory.getLogger(AnalyseStageHandler.class);

    private final AnalysisDataLoader dataLoader;
    private final MetricsService metricsService;
    private final GatewayCheckService gatewayCheckService;
    private final RedFlagService redFlagService;
    private final CsabScoringService scoringService;
    private final GatewayCheckRepository gatewayChecks;
    private final FindingRepository findings;
    private final AuditRepository audits;
    private final RubricLoader rubricLoader;
    private final TenantTx tenantTx;
    private final ObjectMapper objectMapper;

    public AnalyseStageHandler(AnalysisDataLoader dataLoader, MetricsService metricsService,
                               GatewayCheckService gatewayCheckService, RedFlagService redFlagService,
                               CsabScoringService scoringService, GatewayCheckRepository gatewayChecks,
                               FindingRepository findings, AuditRepository audits,
                               RubricLoader rubricLoader, TenantTx tenantTx, ObjectMapper objectMapper) {
        this.dataLoader = dataLoader;
        this.metricsService = metricsService;
        this.gatewayCheckService = gatewayCheckService;
        this.redFlagService = redFlagService;
        this.scoringService = scoringService;
        this.gatewayChecks = gatewayChecks;
        this.findings = findings;
        this.audits = audits;
        this.rubricLoader = rubricLoader;
        this.tenantTx = tenantTx;
        this.objectMapper = objectMapper;
    }

    @Override
    public Audit.Stage stage() {
        return Audit.Stage.ANALYSE;
    }

    @Override
    public void run(Audit audit, Journal journal) {
        if (gatewayChecks.existsByAuditId(audit.getId())) {
            return; // analysis already ran for this audit (resume)
        }
        Rubric rubric = rubricLoader.active();
        freezeVersions(audit, rubric);

        AnalysisData data = dataLoader.load(audit, journal);
        Map<String, MetricsService.MetricValue> metrics = metricsService.compute(audit, data, rubric);
        Map<String, Outcome> gateway = gatewayCheckService.run(audit, data, metrics, rubric);
        redFlagService.run(audit, data, metrics, rubric);
        List<Finding> auditFindings = findings.findByJournalId(journal.getId()).stream()
                .filter(f -> audit.getId().equals(f.getAuditId()))
                .toList();
        scoringService.score(audit, data, metrics, gateway, auditFindings, rubric);
        log.info("Audit {} analysed: gateway={}, findings={}", audit.getId(), gateway,
                auditFindings.size());
    }

    private void freezeVersions(Audit audit, Rubric rubric) {
        String detectors;
        try {
            detectors = objectMapper.writeValueAsString(Map.of(
                    "identity", "identity/1.0.0",
                    "crawl", "crawl/1.0.0",
                    "reconcile", ReconciliationService.DETECTOR_VERSION,
                    "red-flags", RedFlagService.DETECTOR_VERSION));
        } catch (JsonProcessingException e) {
            detectors = "{}";
        }
        String detectorsJson = detectors;
        tenantTx.asSystem(() -> audits.findById(audit.getId()).ifPresent(a ->
                a.freezeVersions(rubric.version(), detectorsJson)));
    }
}
