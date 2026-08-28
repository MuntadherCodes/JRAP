package dev.hmcodes.jrap.crawl.pipeline;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.tenancy.service.SecurityAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Audit lifecycle: create, cancel (FR-DASH/API surface; pipeline stages run in AuditRunner). */
@Service
public class AuditService {

    private final AuditRepository audits;
    private final JournalRepository journals;
    private final SecurityAuditService securityAudit;
    private final Clock clock;
    private final int defaultPageCap;

    public AuditService(AuditRepository audits, JournalRepository journals,
                        SecurityAuditService securityAudit, Clock clock,
                        @Value("${jrap.crawl.page-cap-default:3000}") int defaultPageCap) {
        this.audits = audits;
        this.journals = journals;
        this.securityAudit = securityAudit;
        this.clock = clock;
        this.defaultPageCap = defaultPageCap;
    }

    @Transactional
    public Audit create(UUID journalId, UUID actorUserId, String actorEmail) {
        UUID orgId = TenantContext.requireOrganisationId();
        Journal journal = journals.findById(journalId)
                .filter(j -> j.getOrganisationId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("journal-not-found", "Journal not found"));
        if (journal.getStatus() != Journal.Status.ACTIVE) {
            throw ApiException.conflict("journal-archived", "An archived journal cannot be audited");
        }
        String crawlBase = journal.getHomepageUrl() != null ? journal.getHomepageUrl()
                : journal.getRegisteredInput();
        if (dev.hmcodes.jrap.crawl.service.CrawlService.normaliseUrl(crawlBase) == null) {
            throw ApiException.conflict("no-crawlable-homepage",
                    "No homepage URL is known for this journal, so it cannot be crawled. "
                            + "Re-register it by URL or wait for a source to state its homepage.");
        }
        if (audits.existsByJournalIdAndStatusIn(journalId,
                List.of(Audit.Status.PENDING, Audit.Status.RUNNING))) {
            throw ApiException.conflict("audit-in-progress",
                    "An audit for this journal is already pending or running");
        }
        Audit audit = new Audit(UUID.randomUUID(), orgId, journalId, defaultPageCap,
                actorUserId, clock.instant());
        audits.save(audit);
        securityAudit.record("AUDIT_CREATED", orgId, actorUserId, actorEmail,
                Map.of("auditId", audit.getId().toString(), "journalId", journalId.toString()), null);
        return audit;
    }

    @Transactional
    public void cancel(UUID auditId, UUID actorUserId, String actorEmail) {
        UUID orgId = TenantContext.requireOrganisationId();
        Audit audit = audits.findById(auditId)
                .filter(a -> a.getOrganisationId().equals(orgId))
                .orElseThrow(() -> ApiException.notFound("audit-not-found", "Audit not found"));
        if (audit.getStatus() != Audit.Status.PENDING && audit.getStatus() != Audit.Status.RUNNING) {
            throw ApiException.conflict("audit-not-cancellable",
                    "Only pending or running audits can be cancelled");
        }
        audit.markCancelled(clock.instant());
        securityAudit.record("AUDIT_CANCELLED", orgId, actorUserId, actorEmail,
                Map.of("auditId", auditId.toString()), null);
    }
}
