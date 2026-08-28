package dev.hmcodes.jrap.registry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.util.Issn;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.integrations.dto.JournalSourceIdentity;
import dev.hmcodes.jrap.integrations.dto.SourceAvailability;
import dev.hmcodes.jrap.integrations.dto.SourceResult;
import dev.hmcodes.jrap.integrations.source.CrossrefAdapter;
import dev.hmcodes.jrap.integrations.source.DoajAdapter;
import dev.hmcodes.jrap.integrations.source.IssnPortalAdapter;
import dev.hmcodes.jrap.integrations.source.OpenAlexAdapter;
import dev.hmcodes.jrap.integrations.source.SiteProbe;
import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import dev.hmcodes.jrap.registry.domain.EvidenceLink;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.domain.JournalIdentityRecord;
import dev.hmcodes.jrap.registry.repo.EvidenceItemRepository;
import dev.hmcodes.jrap.registry.repo.EvidenceLinkRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import dev.hmcodes.jrap.registry.repo.JournalIdentityRecordRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.registry.repo.OrgQuotaRepository;
import dev.hmcodes.jrap.tenancy.service.SecurityAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * FR-JRN-1: register a journal by ISSN or homepage URL and resolve its identity across
 * ISSN Portal, Crossref, DOAJ and OpenAlex; FR-JRN-2: record identity inconsistencies
 * as findings; FR-JRN-3: quota enforcement (admin-set during beta).
 *
 * <p>Source fetches run OUTSIDE the write transaction (they are slow and independently
 * cached as immutable ApiRecords); the journal, identity records, evidence and findings
 * are then persisted in one short tenant-scoped transaction.</p>
 */
@Service
public class JournalRegistrationService {

    private final JournalRepository journals;
    private final JournalIdentityRecordRepository identityRecords;
    private final FindingRepository findings;
    private final EvidenceItemRepository evidenceItems;
    private final EvidenceLinkRepository evidenceLinks;
    private final OrgQuotaRepository quotas;
    private final OpenAlexAdapter openAlex;
    private final CrossrefAdapter crossref;
    private final DoajAdapter doaj;
    private final IssnPortalAdapter issnPortal;
    private final SiteProbe siteProbe;
    private final IdentityConsistencyChecker checker;
    private final SecurityAuditService audit;
    private final TransactionTemplate writeTx;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int defaultMaxJournals;

    public JournalRegistrationService(JournalRepository journals,
                                      JournalIdentityRecordRepository identityRecords,
                                      FindingRepository findings,
                                      EvidenceItemRepository evidenceItems,
                                      EvidenceLinkRepository evidenceLinks,
                                      OrgQuotaRepository quotas,
                                      OpenAlexAdapter openAlex, CrossrefAdapter crossref,
                                      DoajAdapter doaj, IssnPortalAdapter issnPortal,
                                      SiteProbe siteProbe, IdentityConsistencyChecker checker,
                                      SecurityAuditService audit,
                                      PlatformTransactionManager transactionManager,
                                      ObjectMapper objectMapper, Clock clock,
                                      @Value("${jrap.quotas.max-journals-default:10}") int defaultMaxJournals) {
        this.journals = journals;
        this.identityRecords = identityRecords;
        this.findings = findings;
        this.evidenceItems = evidenceItems;
        this.evidenceLinks = evidenceLinks;
        this.quotas = quotas;
        this.openAlex = openAlex;
        this.crossref = crossref;
        this.doaj = doaj;
        this.issnPortal = issnPortal;
        this.siteProbe = siteProbe;
        this.checker = checker;
        this.audit = audit;
        this.writeTx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.defaultMaxJournals = defaultMaxJournals;
    }

    public Journal register(String issnInput, String urlInput, UUID actorUserId, String actorEmail) {
        UUID orgId = TenantContext.requireOrganisationId();
        if ((issnInput == null) == (urlInput == null)) {
            throw ApiException.badRequest("issn-or-url", "Provide exactly one of 'issn' or 'url'");
        }
        enforceQuota(orgId);

        String issn;
        SourceResult<SiteProbe.SiteIdentity> siteResult = null;
        if (issnInput != null) {
            issn = Issn.normalise(issnInput);
            if (issn == null) {
                throw ApiException.badRequest("invalid-issn",
                        "'" + issnInput + "' is not a valid ISSN (checksum or format failed)");
            }
        } else {
            validateUrl(urlInput);
            siteResult = siteProbe.probe(urlInput);
            if (siteResult.availability() != SourceAvailability.OK) {
                throw ApiException.badRequest("site-unreachable",
                        "The homepage could not be fetched (unreachable, or disallowed by robots.txt). "
                                + "Register the journal by its ISSN instead.");
            }
            issn = siteResult.data().issns().stream()
                    .map(Issn::normalise)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest("no-issn-on-page",
                            "No valid ISSN was found on the page. Register the journal by its ISSN instead."));
        }

        // --- resolution phase (network, outside the write transaction) ---
        Map<String, SourceResult<JournalSourceIdentity>> results = new LinkedHashMap<>();
        results.put(OpenAlexAdapter.SOURCE, openAlex.resolveJournalByIssn(issn));
        results.put(CrossrefAdapter.SOURCE, crossref.resolveJournalByIssn(issn));
        results.put(DoajAdapter.SOURCE, doaj.resolveJournalByIssn(issn));
        results.put(IssnPortalAdapter.SOURCE, issnPortal.resolveJournalByIssn(issn));

        boolean anyResolved = results.values().stream()
                .anyMatch(r -> r.availability() == SourceAvailability.OK);
        if (!anyResolved && siteResult == null) {
            boolean allNotFound = results.values().stream()
                    .allMatch(r -> r.availability() == SourceAvailability.NOT_FOUND);
            if (allNotFound) {
                throw ApiException.notFound("journal-not-found",
                        "No scholarly source has a record for ISSN " + issn);
            }
            throw new ApiException(503, "sources-unavailable",
                    "No scholarly source could be reached to resolve ISSN " + issn + ". Try again later.");
        }

        // Best-effort homepage probe for ISSN registrations, when a source states one.
        if (siteResult == null) {
            String homepage = firstNonNull(
                    identity(results, OpenAlexAdapter.SOURCE, JournalSourceIdentity::homepageUrl),
                    identity(results, DoajAdapter.SOURCE, JournalSourceIdentity::homepageUrl));
            if (homepage != null) {
                siteResult = siteProbe.probe(homepage);
            }
        }
        SourceResult<SiteProbe.SiteIdentity> site = siteResult;

        // --- persistence phase (one short tenant transaction) ---
        String registeredInput = issnInput != null ? issn : urlInput;
        try {
            Journal saved = writeTx.execute(status ->
                    persist(orgId, registeredInput, issn, results, site));
            audit.record("JOURNAL_REGISTERED", orgId, actorUserId, actorEmail,
                    Map.of("journalId", saved.getId().toString(), "issn", issn), null);
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("journal-already-registered",
                    "This journal is already registered in your organisation");
        }
    }

    private Journal persist(UUID orgId, String registeredInput, String issn,
                            Map<String, SourceResult<JournalSourceIdentity>> results,
                            SourceResult<SiteProbe.SiteIdentity> site) {
        // Re-check inside the write transaction: the pre-network check is advisory only
        // (two concurrent registrations could both have passed it).
        enforceQuota(orgId);
        Instant now = clock.instant();
        Journal journal = new Journal(UUID.randomUUID(), orgId, registeredInput, now);

        Map<String, IdentityConsistencyChecker.SourceStatement> statements = new LinkedHashMap<>();
        List<EvidenceItem> evidence = new ArrayList<>();
        List<JournalIdentityRecord> records = new ArrayList<>();

        results.forEach((source, result) -> {
            JournalIdentityRecord record = new JournalIdentityRecord(UUID.randomUUID(), orgId,
                    journal.getId(), source, result.availability(), result.apiRecordId(),
                    result.retrievedAt() != null ? result.retrievedAt() : now);
            UUID evidenceId = null;
            if (result.apiRecordId() != null) {
                EvidenceItem item = new EvidenceItem(UUID.randomUUID(), orgId, journal.getId(),
                        EvidenceItem.Type.API_RECORD, result.apiRecordId(), source,
                        excerptFor(source, result), record.getRetrievedAt(), now);
                evidence.add(item);
                evidenceId = item.getId();
            }
            if (result.availability() == SourceAvailability.OK) {
                JournalSourceIdentity identity = result.data();
                record.setTitle(identity.title());
                record.setPublisher(identity.publisher());
                record.setCountry(identity.country());
                record.setIssnPrint(identity.issnPrint());
                record.setIssnOnline(identity.issnOnline());
                record.setIssnL(identity.issnL());
                record.setExtra(toJson(identity.extra()));
                statements.put(source, new IdentityConsistencyChecker.SourceStatement(
                        identity, SourceAvailability.OK, evidenceId));
            } else {
                statements.put(source, new IdentityConsistencyChecker.SourceStatement(
                        null, result.availability(), evidenceId));
            }
            records.add(record);
        });

        if (site != null) {
            JournalIdentityRecord record = new JournalIdentityRecord(UUID.randomUUID(), orgId,
                    journal.getId(), SiteProbe.SOURCE, site.availability(), site.apiRecordId(),
                    site.retrievedAt() != null ? site.retrievedAt() : now);
            UUID evidenceId = null;
            if (site.apiRecordId() != null) {
                EvidenceItem item = new EvidenceItem(UUID.randomUUID(), orgId, journal.getId(),
                        EvidenceItem.Type.API_RECORD, site.apiRecordId(), SiteProbe.SOURCE,
                        site.availability() == SourceAvailability.OK
                                ? "Homepage: title=\"" + site.data().pageTitle() + "\", ISSNs="
                                        + site.data().issns() + ", generator=" + site.data().platform()
                                : "Homepage fetch: " + site.availability(),
                        record.getRetrievedAt(), now);
                evidence.add(item);
                evidenceId = item.getId();
            }
            if (site.availability() == SourceAvailability.OK) {
                SiteProbe.SiteIdentity siteIdentity = site.data();
                record.setTitle(siteIdentity.pageTitle());
                record.setExtra(toJson(Map.of(
                        "issns", siteIdentity.issns(),
                        "platform", siteIdentity.platform() == null ? "" : siteIdentity.platform())));
                statements.put(SiteProbe.SOURCE, new IdentityConsistencyChecker.SourceStatement(
                        new JournalSourceIdentity(SiteProbe.SOURCE, siteIdentity.url(),
                                siteIdentity.pageTitle(), null, null, null, null, null,
                                siteIdentity.issns(), siteIdentity.url(), Map.of()),
                        SourceAvailability.OK, evidenceId));
            }
            records.add(record);
        }

        mergeIdentity(journal, issn, statements, site);
        // saveAndFlush so a duplicate registration surfaces here as a translated
        // DataIntegrityViolationException (→ 409), not as an opaque commit-time failure.
        journals.saveAndFlush(journal);
        identityRecords.saveAll(records);
        evidenceItems.saveAll(evidence);

        List<DraftFinding> drafts = checker.check(issn, statements);
        Instant createdAt = clock.instant();
        for (DraftFinding draft : drafts) {
            Finding finding = new Finding(UUID.randomUUID(), orgId, journal.getId(),
                    IdentityConsistencyChecker.CATEGORY, draft.code(), draft.severity(),
                    Finding.Status.AUTO, draft.title(), draft.description(),
                    IdentityConsistencyChecker.DETECTOR_VERSION, createdAt);
            findings.save(finding);
            for (UUID evidenceId : draft.evidenceItemIds()) {
                evidenceLinks.save(new EvidenceLink(finding.getId(), evidenceId, orgId));
            }
        }
        return journal;
    }

    private void mergeIdentity(Journal journal, String issn,
                               Map<String, IdentityConsistencyChecker.SourceStatement> statements,
                               SourceResult<SiteProbe.SiteIdentity> site) {
        journal.setTitle(firstNonBlank(
                stated(statements, "CROSSREF", JournalSourceIdentity::title),
                stated(statements, "ISSN_PORTAL", JournalSourceIdentity::title),
                stated(statements, "OPENALEX", JournalSourceIdentity::title),
                stated(statements, "DOAJ", JournalSourceIdentity::title),
                stated(statements, "SITE", JournalSourceIdentity::title)));
        journal.setPublisher(firstNonBlank(
                stated(statements, "CROSSREF", JournalSourceIdentity::publisher),
                stated(statements, "DOAJ", JournalSourceIdentity::publisher),
                stated(statements, "OPENALEX", JournalSourceIdentity::publisher)));
        journal.setCountry(firstNonBlank(
                stated(statements, "DOAJ", JournalSourceIdentity::country),
                stated(statements, "OPENALEX", JournalSourceIdentity::country)));
        journal.setIssnPrint(firstNonBlank(
                stated(statements, "CROSSREF", JournalSourceIdentity::issnPrint),
                stated(statements, "DOAJ", JournalSourceIdentity::issnPrint)));
        journal.setIssnOnline(firstNonBlank(
                stated(statements, "CROSSREF", JournalSourceIdentity::issnOnline),
                stated(statements, "DOAJ", JournalSourceIdentity::issnOnline)));
        String issnL = firstNonBlank(
                stated(statements, "OPENALEX", JournalSourceIdentity::issnL),
                stated(statements, "ISSN_PORTAL", JournalSourceIdentity::issnL));
        journal.setIssnL(issnL != null ? issnL : issn);
        journal.setHomepageUrl(firstNonBlank(
                stated(statements, "OPENALEX", JournalSourceIdentity::homepageUrl),
                stated(statements, "DOAJ", JournalSourceIdentity::homepageUrl),
                site != null && site.data() != null ? site.data().url() : null));
        if (site != null && site.availability() == SourceAvailability.OK) {
            journal.setPlatform(site.data().platform());
        }
        IdentityConsistencyChecker.SourceStatement openalex = statements.get("OPENALEX");
        if (openalex != null && openalex.identity() != null) {
            journal.setOpenalexId(openalex.identity().sourceId());
        }
        IdentityConsistencyChecker.SourceStatement doajStatement = statements.get("DOAJ");
        if (doajStatement != null && doajStatement.identity() != null) {
            journal.setDoajId(doajStatement.identity().sourceId());
            journal.setInDoaj(true);
        }
        journal.setInCrossref(statements.containsKey("CROSSREF")
                && statements.get("CROSSREF").identity() != null);

        Set<String> variants = new LinkedHashSet<>();
        statements.values().forEach(s -> {
            if (s.identity() != null && s.identity().title() != null && !s.identity().title().isBlank()) {
                variants.add(s.identity().title().trim());
            }
        });
        journal.setTitleVariants(toJson(List.copyOf(variants)));
    }

    private void enforceQuota(UUID orgId) {
        int max = quotas.findById(orgId).map(q -> q.getMaxJournals()).orElse(defaultMaxJournals);
        long active = journals.countByOrganisationIdAndStatus(orgId, Journal.Status.ACTIVE);
        if (active >= max) {
            // FR-BILL-2 graceful quota behaviour; upgrade flows arrive with billing (Phase 10).
            throw ApiException.forbidden("quota-reached",
                    "Your organisation has reached its journal quota (" + max
                            + "). Contact the platform operator to raise it.");
        }
    }

    private String excerptFor(String source, SourceResult<JournalSourceIdentity> result) {
        if (result.availability() != SourceAvailability.OK) {
            return source + " lookup: " + result.availability();
        }
        JournalSourceIdentity identity = result.data();
        return source + " states: title=\"" + identity.title() + "\", publisher=\""
                + identity.publisher() + "\", ISSNs=" + identity.issns()
                + (identity.issnL() != null ? ", ISSN-L=" + identity.issnL() : "");
    }

    private interface Field {
        String get(JournalSourceIdentity identity);
    }

    private static String stated(Map<String, IdentityConsistencyChecker.SourceStatement> statements,
                                 String source, Field field) {
        IdentityConsistencyChecker.SourceStatement statement = statements.get(source);
        return statement == null || statement.identity() == null ? null : field.get(statement.identity());
    }

    private static String identity(Map<String, SourceResult<JournalSourceIdentity>> results,
                                   String source, Field field) {
        SourceResult<JournalSourceIdentity> result = results.get(source);
        return result == null || result.data() == null ? null : field.get(result.data());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("scheme");
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid-url", "'" + url + "' is not a valid http(s) URL");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
