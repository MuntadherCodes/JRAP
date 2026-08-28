package dev.hmcodes.jrap.api.controller;

import dev.hmcodes.jrap.api.security.AuthPrincipal;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.crawl.domain.CrawlTask;
import dev.hmcodes.jrap.crawl.pipeline.AuditService;
import dev.hmcodes.jrap.crawl.repo.CrawlTaskRepository;
import dev.hmcodes.jrap.crawl.repo.SnapshotRepository;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Audit lifecycle endpoints: create, status (live progress, NFR-PERF-1), inventory, cancel. */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final AuditService auditService;
    private final AuditRepository audits;
    private final SnapshotRepository snapshots;
    private final CrawlTaskRepository crawlTasks;

    public AuditController(AuditService auditService, AuditRepository audits,
                           SnapshotRepository snapshots, CrawlTaskRepository crawlTasks) {
        this.auditService = auditService;
        this.audits = audits;
        this.snapshots = snapshots;
        this.crawlTasks = crawlTasks;
    }

    public record AuditDto(UUID id, UUID journalId, Audit.Status status, Audit.Stage stage,
                           int pageCap, int pagesFetched, int pagesSkipped, String error,
                           Instant createdAt, Instant startedAt, Instant finishedAt) {
        static AuditDto from(Audit a) {
            return new AuditDto(a.getId(), a.getJournalId(), a.getStatus(), a.getStage(),
                    a.getPageCap(), a.getPagesFetched(), a.getPagesSkipped(), a.getError(),
                    a.getCreatedAt(), a.getStartedAt(), a.getFinishedAt());
        }
    }

    @PostMapping("/journals/{journalId}/audits")
    @PreAuthorize("hasAnyRole('OWNER', 'ANALYST')")
    @ResponseStatus(HttpStatus.CREATED)
    public AuditDto create(@AuthenticationPrincipal AuthPrincipal principal,
                           @PathVariable UUID journalId) {
        return AuditDto.from(auditService.create(journalId, principal.userId(), principal.email()));
    }

    @GetMapping("/journals/{journalId}/audits")
    @Transactional(readOnly = true)
    public List<AuditDto> listForJournal(@PathVariable UUID journalId) {
        return audits.findByJournalIdOrderByCreatedAtDesc(journalId).stream()
                .filter(a -> a.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .map(AuditDto::from).toList();
    }

    @GetMapping("/audits/{id}")
    @Transactional(readOnly = true)
    public AuditDto get(@PathVariable UUID id) {
        return AuditDto.from(requireAudit(id));
    }

    public record SnapshotDto(UUID id, String url, int httpStatus, String contentType,
                              String pageType, Instant fetchedAt) {}

    @GetMapping("/audits/{id}/snapshots")
    @Transactional(readOnly = true)
    public List<SnapshotDto> snapshotInventory(@PathVariable UUID id) {
        requireAudit(id);
        return snapshots.findByAuditIdOrderByFetchedAt(id).stream()
                .map(s -> new SnapshotDto(s.getId(), s.getUrl(), s.getHttpStatus(),
                        s.getContentType(), s.getPageType(), s.getFetchedAt()))
                .toList();
    }

    public record SkippedUrlDto(String url, String status, String reason, Instant at) {}

    /** FR-CRWL-4: every skipped or blocked URL with its recorded reason — never a silent gap. */
    @GetMapping("/audits/{id}/skipped")
    @Transactional(readOnly = true)
    public List<SkippedUrlDto> skipped(@PathVariable UUID id) {
        requireAudit(id);
        return crawlTasks.findByAuditIdAndStatusIn(id,
                        List.of(CrawlTask.Status.SKIPPED, CrawlTask.Status.FAILED)).stream()
                .map(t -> new SkippedUrlDto(t.getUrl(), t.getStatus().name(), t.getSkipReason(),
                        t.getFetchedAt()))
                .toList();
    }

    @PostMapping("/audits/{id}/cancel")
    @PreAuthorize("hasAnyRole('OWNER', 'ANALYST')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        auditService.cancel(id, principal.userId(), principal.email());
    }

    private Audit requireAudit(UUID id) {
        return audits.findById(id)
                .filter(a -> a.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));
    }
}
