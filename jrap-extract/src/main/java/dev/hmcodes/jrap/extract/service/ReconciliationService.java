package dev.hmcodes.jrap.extract.service;

import dev.hmcodes.jrap.extract.domain.Article;
import dev.hmcodes.jrap.extract.domain.AuthorSlot;
import dev.hmcodes.jrap.extract.repo.ArticleRepository;
import dev.hmcodes.jrap.extract.repo.AuthorSlotRepository;
import dev.hmcodes.jrap.extract.util.TextMatch;
import dev.hmcodes.jrap.integrations.dto.SourceAvailability;
import dev.hmcodes.jrap.integrations.dto.SourceResult;
import dev.hmcodes.jrap.integrations.source.CrossrefAdapter;
import dev.hmcodes.jrap.integrations.source.OpenAlexAdapter;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.EvidenceLink;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR-EXT-5: reconciles site-extracted article records with Crossref and OpenAlex for
 * the same DOI (sampled, budgeted); mismatches become category "metadata" findings with
 * dual evidence — the site snapshot and the API record. Runs once per audit (the
 * category guard makes a resumed ENRICH stage idempotent). Source outages degrade
 * silently (FR-INT-6) — absence of a check is never reported as agreement.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    public static final String DETECTOR_VERSION = "reconcile/1.0.0";
    public static final String CATEGORY = "metadata";

    private final ArticleRepository articles;
    private final AuthorSlotRepository authorSlots;
    private final CrossrefAdapter crossref;
    private final OpenAlexAdapter openAlex;
    private final FindingRepository findings;
    private final EvidenceItemRepository evidenceItems;
    private final EvidenceLinkRepository evidenceLinks;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int sampleSize;

    public ReconciliationService(ArticleRepository articles, AuthorSlotRepository authorSlots,
                                 CrossrefAdapter crossref, OpenAlexAdapter openAlex,
                                 FindingRepository findings, EvidenceItemRepository evidenceItems,
                                 EvidenceLinkRepository evidenceLinks,
                                 PlatformTransactionManager transactionManager, Clock clock,
                                 @Value("${jrap.extract.reconciliation-sample:25}") int sampleSize) {
        this.articles = articles;
        this.authorSlots = authorSlots;
        this.crossref = crossref;
        this.openAlex = openAlex;
        this.findings = findings;
        this.evidenceItems = evidenceItems;
        this.evidenceLinks = evidenceLinks;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.sampleSize = sampleSize;
    }

    public void run(Audit audit) {
        if (findings.existsByAuditIdAndCategory(audit.getId(), CATEGORY)) {
            return; // reconciliation already ran for this audit
        }
        List<Article> withDoi = articles.findByAuditIdAndDoiIsNotNullOrderByCreatedAt(audit.getId());
        List<Article> sample = withDoi.size() > sampleSize ? withDoi.subList(0, sampleSize) : withDoi;
        for (Article article : sample) {
            try {
                reconcileOne(audit, article);
            } catch (Exception e) {
                log.warn("Reconciliation failed for DOI {}: {}", article.getDoi(), e.getMessage());
            }
        }
    }

    private void reconcileOne(Audit audit, Article article) {
        List<AuthorSlot> slots = authorSlots.findByArticleIdInOrderByArticleIdAscPositionAsc(
                List.of(article.getId()));

        SourceResult<CrossrefAdapter.WorkRecord> crossrefWork = crossref.workByDoi(article.getDoi());
        if (crossrefWork.availability() == SourceAvailability.OK) {
            CrossrefAdapter.WorkRecord work = crossrefWork.data();
            compareTitle(audit, article, "CROSSREF", work.title(), crossrefWork.apiRecordId());
            compareAuthorCount(audit, article, "CROSSREF", slots.size(), work.authorCount(),
                    crossrefWork.apiRecordId());
            compareYear(audit, article, "CROSSREF", work.publishedYear(), crossrefWork.apiRecordId());
        } else if (crossrefWork.availability() == SourceAvailability.NOT_FOUND) {
            recordFinding(audit, article, "EXT_DOI_NOT_IN_CROSSREF", Finding.Severity.MEDIUM,
                    "DOI not found in Crossref",
                    "The article displays DOI " + article.getDoi()
                            + " but Crossref has no record of it — the DOI may be unregistered.",
                    crossrefWork.apiRecordId());
        }

        SourceResult<OpenAlexAdapter.WorkRecord> openAlexWork = openAlex.workByDoi(article.getDoi());
        if (openAlexWork.availability() == SourceAvailability.OK) {
            OpenAlexAdapter.WorkRecord work = openAlexWork.data();
            compareTitle(audit, article, "OPENALEX", work.title(), openAlexWork.apiRecordId());
            compareAuthorCount(audit, article, "OPENALEX", slots.size(), work.authorCount(),
                    openAlexWork.apiRecordId());
            compareYear(audit, article, "OPENALEX", work.publishedYear(), openAlexWork.apiRecordId());
        }
    }

    private void compareTitle(Audit audit, Article article, String source, String sourceTitle,
                              UUID apiRecordId) {
        if (article.getTitle() == null || sourceTitle == null) {
            return;
        }
        if (!TextMatch.roughlyEqual(article.getTitle(), sourceTitle)) {
            recordFinding(audit, article, "EXT_TITLE_MISMATCH_" + source, Finding.Severity.MEDIUM,
                    "Article title differs from " + source,
                    "The site shows \"" + article.getTitle() + "\" but " + source + " records \""
                            + sourceTitle + "\" for DOI " + article.getDoi() + ".",
                    apiRecordId);
        }
    }

    private void compareAuthorCount(Audit audit, Article article, String source, int siteCount,
                                    int sourceCount, UUID apiRecordId) {
        if (siteCount == 0 || sourceCount == 0 || siteCount == sourceCount) {
            return;
        }
        recordFinding(audit, article, "EXT_AUTHOR_COUNT_MISMATCH_" + source, Finding.Severity.MEDIUM,
                "Author count differs from " + source,
                "The site lists " + siteCount + " author(s) but " + source + " records " + sourceCount
                        + " for DOI " + article.getDoi() + ".",
                apiRecordId);
    }

    private void compareYear(Audit audit, Article article, String source, Integer sourceYear,
                             UUID apiRecordId) {
        if (sourceYear == null || article.getDatePublished() == null) {
            return;
        }
        java.util.regex.Matcher yearMatcher =
                java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(article.getDatePublished());
        if (!yearMatcher.find()) {
            return;
        }
        int siteYear = Integer.parseInt(yearMatcher.group());
        if (Math.abs(siteYear - sourceYear) >= 1) {
            recordFinding(audit, article, "EXT_DATE_MISMATCH_" + source, Finding.Severity.LOW,
                    "Publication year differs from " + source,
                    "The site displays \"" + article.getDatePublished() + "\" but " + source
                            + " records year " + sourceYear + " for DOI " + article.getDoi() + ".",
                    apiRecordId);
        }
    }

    private void recordFinding(Audit audit, Article article, String code, Finding.Severity severity,
                               String title, String description, UUID apiRecordId) {
        Instant now = clock.instant();
        tx.execute(status -> {
            EvidenceItem siteEvidence = new EvidenceItem(UUID.randomUUID(), audit.getOrganisationId(),
                    audit.getJournalId(), EvidenceItem.Type.SNAPSHOT, null, "SITE",
                    "Article page states: title=\"" + article.getTitle() + "\", published="
                            + article.getDatePublished() + ", DOI=" + article.getDoi(),
                    article.getCreatedAt(), now);
            siteEvidence.setSnapshotId(article.getSnapshotId());
            evidenceItems.save(siteEvidence);

            Finding finding = new Finding(UUID.randomUUID(), audit.getOrganisationId(),
                    audit.getJournalId(), CATEGORY, code, severity, Finding.Status.AUTO,
                    title, description, DETECTOR_VERSION, now);
            finding.setAuditId(audit.getId());
            findings.save(finding);
            evidenceLinks.save(new EvidenceLink(finding.getId(), siteEvidence.getId(),
                    audit.getOrganisationId()));

            if (apiRecordId != null) {
                EvidenceItem apiEvidence = new EvidenceItem(UUID.randomUUID(),
                        audit.getOrganisationId(), audit.getJournalId(),
                        EvidenceItem.Type.API_RECORD, apiRecordId, code.contains("OPENALEX")
                                ? "OPENALEX" : "CROSSREF",
                        description, now, now);
                evidenceItems.save(apiEvidence);
                evidenceLinks.save(new EvidenceLink(finding.getId(), apiEvidence.getId(),
                        audit.getOrganisationId()));
            }
            return null;
        });
    }
}
