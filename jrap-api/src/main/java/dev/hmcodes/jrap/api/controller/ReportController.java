package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.api.security.AuthPrincipal;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.reporting.domain.Report;
import dev.hmcodes.jrap.reporting.model.ReportContent;
import dev.hmcodes.jrap.reporting.service.ReportExportService;
import dev.hmcodes.jrap.reporting.service.ReportService;
import dev.hmcodes.jrap.review.service.ReviewService;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Report lifecycle API (FR-RPT-1..7): generate versioned drafts, read structured content
 * with guard outcomes, edit guard-failing sentences, release (FR-REV-4 gate + FR-RPT-4
 * guard re-run), export HTML/DOCX/PDF, and delta against a prior audit.
 */
@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private final ReportService reportService;
    private final ReportExportService exportService;
    private final AuditRepository audits;

    public ReportController(ReportService reportService, ReportExportService exportService,
                            AuditRepository audits) {
        this.reportService = reportService;
        this.exportService = exportService;
        this.audits = audits;
    }

    public record ReportSummaryDto(UUID id, int version, String status, String verdict,
                                   boolean guardPassed, String contentHash,
                                   String narrativePromptVersion, Instant createdAt,
                                   Instant releasedAt) {}

    public record ReportDto(UUID id, UUID auditId, int version, String status, String verdict,
                            boolean guardPassed, String contentHash, String narrativePromptVersion,
                            List<ReportContent.Section> sections,
                            List<ReportContent.RoadmapAction> roadmap,
                            List<ReportContent.Exclusion> exclusions,
                            String guardReport, Instant createdAt, Instant releasedAt) {}

    @PostMapping("/audits/{auditId}/reports")
    public ReportDto generate(@AuthenticationPrincipal AuthPrincipal principal,
                              @PathVariable UUID auditId) {
        requireAudit(auditId);
        Report report = reportService.generate(auditId, actor(principal));
        return dto(report);
    }

    @GetMapping("/audits/{auditId}/reports")
    @Transactional(readOnly = true)
    public List<ReportSummaryDto> list(@PathVariable UUID auditId) {
        requireAudit(auditId);
        return reportService.forAudit(auditId).stream()
                .map(r -> new ReportSummaryDto(r.getId(), r.getVersion(), r.getStatus().name(),
                        r.getVerdict().name(), r.isGuardPassed(), r.getContentHash(),
                        r.getNarrativePromptVersion(), r.getCreatedAt(), r.getReleasedAt()))
                .toList();
    }

    @GetMapping("/reports/{id}")
    @Transactional(readOnly = true)
    public ReportDto get(@PathVariable UUID id) {
        return dto(requireReport(id));
    }

    public record SentenceEditRequest(@NotBlank String sentenceId, String text, Boolean remove) {}

    @PostMapping("/reports/{id}/sentences")
    public ReportDto editSentence(@AuthenticationPrincipal AuthPrincipal principal,
                                  @PathVariable UUID id,
                                  @jakarta.validation.Valid @RequestBody SentenceEditRequest request) {
        actor(principal);
        requireReport(id);
        boolean remove = Boolean.TRUE.equals(request.remove());
        return dto(reportService.editSentence(id, request.sentenceId(), request.text(), remove));
    }

    @PostMapping("/reports/{id}/release")
    public ReportDto release(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        requireReport(id);
        return dto(reportService.release(id, actor(principal)));
    }

    @GetMapping("/reports/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id,
                                         @RequestParam(defaultValue = "html") String format) {
        requireReport(id);
        ReportExportService.Export export = exportService.export(id, format);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        ("html".equals(format) ? "inline" : "attachment")
                                + "; filename=\"" + export.filename() + "\"")
                .contentType(MediaType.parseMediaType(export.contentType()))
                .body(export.bytes());
    }

    public record DeltaDto(UUID auditId, UUID priorAuditId, List<ReportService.ScoreDelta> scores,
                           List<ReportService.GatewayDelta> gateway, List<String> resolvedCodes,
                           List<String> newCodes) {}

    @GetMapping("/audits/{auditId}/delta/{priorAuditId}")
    @Transactional(readOnly = true)
    public DeltaDto delta(@PathVariable UUID auditId, @PathVariable UUID priorAuditId) {
        requireAudit(auditId);
        requireAudit(priorAuditId);
        ReportService.Delta delta = reportService.delta(auditId, priorAuditId);
        return new DeltaDto(delta.auditId(), delta.priorAuditId(), delta.scores(), delta.gateway(),
                delta.resolvedCodes(), delta.newCodes());
    }

    // ------------------------------------------------------------------ helpers

    private ReportDto dto(Report report) {
        return new ReportDto(report.getId(), report.getAuditId(), report.getVersion(),
                report.getStatus().name(), report.getVerdict().name(), report.isGuardPassed(),
                report.getContentHash(), report.getNarrativePromptVersion(),
                reportService.sections(report), reportService.roadmap(report),
                reportService.exclusions(report), report.getGuardReport(),
                report.getCreatedAt(), report.getReleasedAt());
    }

    private ReviewService.Actor actor(AuthPrincipal principal) {
        if (principal.role() == AppUser.Role.VIEWER) {
            throw ApiException.forbidden("viewer-read-only",
                    "Viewers cannot generate, edit, or release reports.");
        }
        return new ReviewService.Actor(principal.userId(), principal.email());
    }

    private void requireAudit(UUID id) {
        audits.findById(id)
                .filter(a -> a.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));
    }

    private Report requireReport(UUID id) {
        Report report = reportService.requireReport(id);
        if (!report.getOrganisationId().equals(TenantContext.requireOrganisationId())) {
            throw ApiException.notFound("report-not-found", "Report not found");
        }
        return report;
    }
}
