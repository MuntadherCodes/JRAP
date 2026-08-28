package dev.hmcodes.jrap.crawl.pipeline;

import dev.hmcodes.jrap.crawl.repo.SnapshotRepository;
import dev.hmcodes.jrap.crawl.service.CrawlService;
import dev.hmcodes.jrap.crawl.service.OaiHarvester;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.EvidenceLink;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.pipeline.AuditStageHandler;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** The CRAWL stage: frontier crawl + OAI-PMH cross-check (FR-CRWL-1..4, 7). */
@Component
public class CrawlStageHandler implements AuditStageHandler {

    private static final Logger log = LoggerFactory.getLogger(CrawlStageHandler.class);
    public static final String CRAWL_DETECTOR_VERSION = "crawl/1.0.0";

    private final CrawlService crawlService;
    private final OaiHarvester oaiHarvester;
    private final SnapshotRepository snapshots;
    private final FindingRepository findings;
    private final EvidenceItemRepository evidenceItems;
    private final EvidenceLinkRepository evidenceLinks;
    private final Clock clock;

    public CrawlStageHandler(CrawlService crawlService, OaiHarvester oaiHarvester,
                             SnapshotRepository snapshots, FindingRepository findings,
                             EvidenceItemRepository evidenceItems, EvidenceLinkRepository evidenceLinks,
                             Clock clock) {
        this.crawlService = crawlService;
        this.oaiHarvester = oaiHarvester;
        this.snapshots = snapshots;
        this.findings = findings;
        this.evidenceItems = evidenceItems;
        this.evidenceLinks = evidenceLinks;
        this.clock = clock;
    }

    @Override
    public Audit.Stage stage() {
        return Audit.Stage.CRAWL;
    }

    @Override
    public void run(Audit audit, Journal journal) {
        CrawlService.CrawlOutcome outcome = crawlService.run(audit, journal);
        if (!outcome.completed()) {
            return; // cancelled — status already set
        }
        String baseUrl = journal.getHomepageUrl() != null ? journal.getHomepageUrl()
                : journal.getRegisteredInput();
        long oaiCount = oaiHarvester.harvest(audit, baseUrl);
        long articleCount = snapshots.countByAuditIdAndPageType(audit.getId(), "article-landing");
        maybeRecordOaiMismatch(audit, oaiCount, articleCount);
        log.info("Audit {} crawl complete: {} pages fetched, {} skipped, {} OAI records",
                audit.getId(), outcome.fetched(), outcome.skipped(), oaiCount);
    }

    /**
     * FR-CRWL-2 cross-check: when OAI-PMH and the HTML crawl disagree substantially on
     * article coverage, record a crawl-coverage finding with COMPUTED evidence.
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
        finding.setAuditId(audit.getId());
        evidenceItems.save(evidence);
        findings.save(finding);
        evidenceLinks.save(new EvidenceLink(finding.getId(), evidence.getId(), audit.getOrganisationId()));
    }
}
