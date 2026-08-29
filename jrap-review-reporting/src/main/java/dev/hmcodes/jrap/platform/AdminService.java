package dev.hmcodes.jrap.platform;

import dev.hmcodes.jrap.aigateway.LlmGateway;
import dev.hmcodes.jrap.aigateway.repo.LlmCallRepository;
import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.integrations.cache.ApiRecordRepository;
import dev.hmcodes.jrap.registry.domain.Journal;
import dev.hmcodes.jrap.registry.domain.OrgQuota;
import dev.hmcodes.jrap.registry.platform.SettingsService;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.JournalRepository;
import dev.hmcodes.jrap.registry.repo.OrgQuotaRepository;
import dev.hmcodes.jrap.tenancy.domain.Organisation;
import dev.hmcodes.jrap.tenancy.repo.OrganisationRepository;
import dev.hmcodes.jrap.tenancy.service.SecurityAuditService;
import dev.hmcodes.jrap.tenancy.service.TenantTx;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-ADM-1/2 + FR-JRN-3: platform-operator functions. Every method here is reachable
 * only through the admin API (config-listed platform admins) and runs under system
 * scope — the operator sees across tenants by design, and every mutation lands in the
 * immutable security audit log.
 */
@Service
public class AdminService {

    public record OrgRow(UUID id, String name, String status, Instant createdAt,
                         int maxJournals, long journals) {}

    public record SourceHealth(String source, boolean everSucceeded, Instant lastRetrievedAt) {}

    private final OrganisationRepository organisations;
    private final OrgQuotaRepository quotas;
    private final JournalRepository journals;
    private final AuditRepository audits;
    private final ApiRecordRepository apiRecords;
    private final LlmCallRepository llmCalls;
    private final LlmGateway llmGateway;
    private final SettingsService settings;
    private final SecurityAuditService securityAudit;
    private final TenantTx tenantTx;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminService(OrganisationRepository organisations, OrgQuotaRepository quotas,
                        JournalRepository journals, AuditRepository audits,
                        ApiRecordRepository apiRecords, LlmCallRepository llmCalls,
                        LlmGateway llmGateway, SettingsService settings,
                        SecurityAuditService securityAudit, TenantTx tenantTx, Clock clock) {
        this.organisations = organisations;
        this.quotas = quotas;
        this.journals = journals;
        this.audits = audits;
        this.apiRecords = apiRecords;
        this.llmCalls = llmCalls;
        this.llmGateway = llmGateway;
        this.settings = settings;
        this.securityAudit = securityAudit;
        this.tenantTx = tenantTx;
        this.clock = clock;
    }

    public List<OrgRow> listOrganisations() {
        return tenantTx.asSystem(() -> {
            List<OrgRow> rows = new ArrayList<>();
            for (Organisation org : organisations.findAll()) {
                int maxJournals = quotas.findById(org.getId())
                        .map(OrgQuota::getMaxJournals).orElse(0);
                long journalCount = journals.findAll().stream()
                        .filter(j -> j.getOrganisationId().equals(org.getId())).count();
                rows.add(new OrgRow(org.getId(), org.getName(), org.getStatus().name(),
                        org.getCreatedAt(), maxJournals, journalCount));
            }
            return rows;
        });
    }

    public void updateQuota(UUID organisationId, int maxJournals, UUID actor, String actorEmail) {
        if (maxJournals < 0 || maxJournals > 10000) {
            throw ApiException.badRequest("bad-quota", "maxJournals must be between 0 and 10000.");
        }
        tenantTx.asSystem(() -> {
            quotas.findById(organisationId).ifPresentOrElse(
                    q -> q.setMaxJournals(maxJournals, clock.instant()),
                    () -> quotas.save(new OrgQuota(organisationId, maxJournals, clock.instant())));
            // Cross-org audit-log insert needs the system scope its REQUIRES_NEW inherits here.
            securityAudit.record("admin.quota-changed", organisationId, actor, actorEmail,
                    Map.of("maxJournals", maxJournals), null);
            return null;
        });
    }

    /** ACTIVE <-> ARCHIVED; archived organisations cannot log in (Phase-1 guard). */
    public void setOrganisationStatus(UUID organisationId, Organisation.Status status,
                                      UUID actor, String actorEmail) {
        if (status == Organisation.Status.PENDING_VERIFICATION) {
            throw ApiException.badRequest("bad-status", "Cannot move an organisation back to pending.");
        }
        tenantTx.asSystem(() -> {
            Organisation org = organisations.findById(organisationId)
                    .orElseThrow(() -> ApiException.notFound("org-not-found", "Organisation not found"));
            org.setStatus(status);
            securityAudit.record("admin.org-status-changed", organisationId, actor, actorEmail,
                    Map.of("status", status.name()), null);
            return null;
        });
    }

    /**
     * FR-JRN-3: transfer a journal between organisations. Beta constraint: only journals
     * with no audits transfer (snapshot rows are write-once, so audit history cannot be
     * re-homed); registration-time identity rows move with the journal.
     */
    @Transactional
    public void transferJournal(UUID journalId, UUID targetOrganisationId,
                                UUID actor, String actorEmail) {
        tenantTx.asSystem(() -> {
            Journal journal = journals.findById(journalId)
                    .orElseThrow(() -> ApiException.notFound("journal-not-found", "Journal not found"));
            organisations.findById(targetOrganisationId)
                    .orElseThrow(() -> ApiException.notFound("org-not-found",
                            "Target organisation not found"));
            if (!audits.findByJournalIdOrderByCreatedAtDesc(journalId).isEmpty()) {
                throw ApiException.conflict("journal-has-audits",
                        "Journals with audit history cannot be transferred (snapshots are"
                                + " write-once); archive it and re-register instead.");
            }
            UUID from = journal.getOrganisationId();
            for (String table : List.of("evidence_link", "evidence_item", "finding",
                    "journal_identity_record")) {
                entityManager.createNativeQuery("update " + table + " set org_id = :target"
                                + (table.equals("evidence_link")
                                        ? " where finding_id in (select id from finding where journal_id = :journal)"
                                        : " where journal_id = :journal"))
                        .setParameter("target", targetOrganisationId)
                        .setParameter("journal", journalId)
                        .executeUpdate();
            }
            entityManager.createNativeQuery(
                            "update journal set org_id = :target where id = :journal")
                    .setParameter("target", targetOrganisationId)
                    .setParameter("journal", journalId)
                    .executeUpdate();
            securityAudit.record("admin.journal-transferred", from, actor, actorEmail,
                    Map.of("journalId", journalId.toString(),
                            "targetOrganisationId", targetOrganisationId.toString()), null);
            return null;
        });
    }

    /** FR-ADM-2: per-source health from the shared api_record cache + the AI gateway. */
    public Map<String, Object> sourceStatus() {
        return tenantTx.asSystem(() -> {
            Map<String, Object> status = new LinkedHashMap<>();
            List<SourceHealth> sources = new ArrayList<>();
            for (String source : List.of("OPENALEX", "CROSSREF", "DOAJ", "ISSN_PORTAL")) {
                var latest = apiRecords.findFirstBySourceOrderByRetrievedAtDesc(source);
                sources.add(new SourceHealth(source, latest.isPresent(),
                        latest.map(r -> r.getRetrievedAt()).orElse(null)));
            }
            status.put("sources", sources);
            status.put("llmEnabled", llmGateway.isEnabled());
            status.put("lastLlmCall", llmCalls.findFirstByOrderByCreatedAtDesc()
                    .map(c -> Map.of("status", c.getStatus(), "at", String.valueOf(c.getCreatedAt())))
                    .orElse(null));
            status.put("settings", settings.all());
            return status;
        });
    }
}
