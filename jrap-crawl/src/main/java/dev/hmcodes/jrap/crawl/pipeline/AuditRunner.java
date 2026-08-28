package dev.hmcodes.jrap.crawl.pipeline;

import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.crawl.repo.OaiHarvestRepository;
import dev.hmcodes.jrap.crawl.repo.SnapshotRepository;
import dev.hmcodes.jrap.crawl.service.CrawlService;
import dev.hmcodes.jrap.crawl.service.OaiHarvester;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.EvidenceLink;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.tenancy.service.TenantTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Single-threaded pipeline worker for the beta deployment: claims the oldest PENDING
 * audit (or resumes a RUNNING one after a restart — NFR-AVL-1) and drives its stages.
 * The claim is race-free within one instance ({@code synchronized} + one scheduler
 * thread); multi-instance claiming (SKIP LOCKED / a queue broker) is a Phase-9
 * scalability concern (NFR-SCAL-1) behind this same entry point.
 */
@Component
public class AuditRunner {

    private static final Logger log = LoggerFactory.getLogger(AuditRunner.class);
    public static final String CRAWL_DETECTOR_VERSION = "crawl/1.0.0";

    private record Claim(UUID auditId, UUID orgId, UUID journalId) {}

    private final AuditRepository audits;
    private final JournalRepository journals;
    private final SnapshotRepository snapshots;
    private final OaiHarvestRepository oaiRecords;
    private final FindingRepository findings;
    private final EvidenceItemRepository evidenceItems;
    private final EvidenceLinkRepository evidenceLinks;
    private final CrawlService crawlService;
    private final OaiHarvester oaiHarvester;
    private final TenantTx tenantTx;
    private final Clock clock;

    public AuditRunner(AuditRepository audits, JournalRepository journals, SnapshotRepository snapshots,
                       OaiHarvestRepository oaiRecords, FindingRepository findings,
                       EvidenceItemRepository evidenceItems, EvidenceLinkRepository evidenceLinks,
                       CrawlService crawlService, OaiHarvester oaiHarvester,
                       TenantTx tenantTx, Clock clock) {
        this.audits = audits;
        this.journals = journals;
        this.snapshots = snapshots;
        this.oaiRecords = oaiRecords;
        this.findings = findings;
        this.evidenceItems = evidenceItems;
        this.evidenceLinks = evidenceLinks;
        this.crawlService = crawlService;
        this.oaiHarvester = oaiHarvester;
        this.tenantTx = tenantTx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${jrap.crawl.poll-interval-ms:5000}")
    public void poll() {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("Audit runner iteration failed", e);
        }
    }

    /** Processes at most one audit to completion. Returns true if an audit was processed. */
    public synchronized boolean runOnce() {
        Claim claim = tenantTx.asSystem(() ->
                audits.findFirstByStatusOrderByCreatedAt(Audit.Status.PENDING)
                        .or(() -> audits.findFirstByStatusOrderByCreatedAt(Audit.Status.RUNNING))
                        .map(audit -> {
                            audit.markRunning(clock.instant());
                            return new Claim(audit.getId(), audit.getOrganisationId(), audit.getJournalId());
                        })
                        .orElse(null));
        if (claim == null) {
            return false;
        }
        TenantContext.setOrganisation(claim.orgId());
        try {
            Audit audit = audits.findById(claim.auditId()).orElseThrow();
            Journal journal = journals.findById(claim.journalId()).orElseThrow();
            runCrawlStage(audit, journal);
            return true;
        } catch (Exception e) {
            log.error("Audit {} failed", claim.auditId(), e);
            tenantTx.asSystem(() -> audits.findById(claim.auditId()).ifPresent(a ->
                    a.markFailed(truncate(e.getMessage()), clock.instant())));
            return true;
        } finally {
            TenantContext.clear();
        }
    }

    private void runCrawlStage(Audit audit, Journal journal) {
        CrawlService.CrawlOutcome outcome = crawlService.run(audit, journal);
        if (!outcome.completed()) {
            return; // cancelled — status already set
        }
        String baseUrl = journal.getHomepageUrl() != null ? journal.getHomepageUrl()
                : journal.getRegisteredInput();
        long oaiCount = oaiHarvester.harvest(audit, baseUrl);
        long articleCount = snapshots.countByAuditIdAndPageType(audit.getId(), "article-landing");
        maybeRecordOaiMismatch(audit, oaiCount, articleCount);

        tenantTx.asSystem(() -> audits.findById(audit.getId()).ifPresent(a -> {
            if (a.getStatus() == Audit.Status.RUNNING) {
                a.markComplete(clock.instant());
            }
        }));
        log.info("Audit {} crawl complete: {} pages fetched, {} skipped, {} OAI records",
                audit.getId(), outcome.fetched(), outcome.skipped(), oaiCount);
    }

    /**
     * FR-CRWL-2 cross-check: when OAI-PMH reports substantially more articles than the
     * HTML crawl found (or vice versa), record a crawl-coverage finding with COMPUTED
     * evidence so the gap is visible, never silent (CON-2 spirit).
     */
    private void maybeRecordOaiMismatch(Audit audit, long oaiCount, long articleCount) {
        if (oaiCount == 0) {
            return; // no OAI endpoint — nothing to cross-check
        }
        long difference = Math.abs(oaiCount - articleCount);
        long tolerance = Math.max(5, Math.round(oaiCount * 0.2));
        if (difference <= tolerance) {
            return;
        }
        if (findings.existsByJournalIdAndCode(audit.getJournalId(), "CRAWL_OAI_HTML_MISMATCH")) {
            return; // already recorded for this journal; re-runs must not duplicate it
        }
        Instant now = clock.instant();
        EvidenceItem evidence = new EvidenceItem(UUID.randomUUID(), audit.getOrganisationId(),
                audit.getJournalId(), EvidenceItem.Type.COMPUTED, null, "CRAWL",
                "OAI-PMH ListRecords returned " + oaiCount + " records; the HTML crawl found "
                        + articleCount + " article landing pages (audit " + audit.getId() + ")",
                now, now);
        Finding finding = new Finding(UUID.randomUUID(), audit.getOrganisationId(),
                audit.getJournalId(), "crawl", "CRAWL_OAI_HTML_MISMATCH", Finding.Severity.LOW,
                Finding.Status.AUTO,
                "OAI-PMH and HTML article counts diverge",
                "The journal's OAI-PMH endpoint lists " + oaiCount + " records but the site crawl "
                        + "found " + articleCount + " article landing pages. Coverage of one of the "
                        + "two views is incomplete (crawl cap, unlinked articles, or stale OAI).",
                CRAWL_DETECTOR_VERSION, now);
        evidenceItems.save(evidence);
        findings.save(finding);
        evidenceLinks.save(new EvidenceLink(finding.getId(), evidence.getId(), audit.getOrganisationId()));
    }

    private static String truncate(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
