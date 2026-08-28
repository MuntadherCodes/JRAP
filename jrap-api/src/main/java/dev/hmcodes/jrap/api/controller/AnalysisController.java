package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.analysis.repo.AnalysisMetricRepository;
import dev.hmcodes.jrap.analysis.repo.CsabScoreRepository;
import dev.hmcodes.jrap.analysis.repo.GatewayCheckRepository;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Analysis results per audit (FR-ANL-1..5): gateway table, CSAB scores, metrics, red flags. */
@RestController
@RequestMapping("/api/v1/audits/{auditId}")
public class AnalysisController {

    private final AuditRepository audits;
    private final GatewayCheckRepository gatewayChecks;
    private final CsabScoreRepository scores;
    private final AnalysisMetricRepository metrics;
    private final FindingRepository findings;

    public AnalysisController(AuditRepository audits, GatewayCheckRepository gatewayChecks,
                              CsabScoreRepository scores, AnalysisMetricRepository metrics,
                              FindingRepository findings) {
        this.audits = audits;
        this.gatewayChecks = gatewayChecks;
        this.scores = scores;
        this.metrics = metrics;
        this.findings = findings;
    }

    public record GatewayDto(String code, String outcome, String summary) {}

    public record ScoreDto(String category, int score, String criteria) {}

    public record MetricDto(String name, BigDecimal value, String detail) {}

    public record AnalysisDto(String rubricVersion, List<GatewayDto> gateway, List<ScoreDto> scores,
                              List<MetricDto> metrics) {}

    @GetMapping("/analysis")
    @Transactional(readOnly = true)
    public AnalysisDto analysis(@PathVariable UUID auditId) {
        Audit audit = requireAudit(auditId);
        return new AnalysisDto(
                audit.getRubricVersion(),
                gatewayChecks.findByAuditIdOrderByCode(auditId).stream()
                        .map(g -> new GatewayDto(g.getCode(), g.getOutcome(), g.getSummary())).toList(),
                scores.findByAuditIdOrderByCategory(auditId).stream()
                        .map(s -> new ScoreDto(s.getCategory(), s.getScore(), s.getCriteria())).toList(),
                metrics.findByAuditIdOrderByName(auditId).stream()
                        .map(m -> new MetricDto(m.getName(), m.getValue(), m.getDetail())).toList());
    }

    public record AuditFindingDto(UUID id, String category, String code, Finding.Severity severity,
                                  Finding.Status status, String title, String description,
                                  String detectorVersion, Instant createdAt) {}

    @GetMapping("/findings")
    @Transactional(readOnly = true)
    public List<AuditFindingDto> auditFindings(@PathVariable UUID auditId) {
        Audit audit = requireAudit(auditId);
        return findings.findByJournalId(audit.getJournalId()).stream()
                .filter(f -> auditId.equals(f.getAuditId()))
                .sorted(Comparator.comparing(Finding::getSeverity)
                        .thenComparing(Finding::getCreatedAt))
                .map(f -> new AuditFindingDto(f.getId(), f.getCategory(), f.getCode(), f.getSeverity(),
                        f.getStatus(), f.getTitle(), f.getDescription(), f.getDetectorVersion(),
                        f.getCreatedAt()))
                .toList();
    }

    private Audit requireAudit(UUID id) {
        return audits.findById(id)
                .filter(a -> a.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));
    }
}
