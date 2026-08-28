package dev.hmcodes.jrap.registry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-set per-organisation quotas (FR-JRN-3; FR-BILL-2 mechanism only — plans and
 * billing arrive in Phase 10; during beta the platform admin sets these directly).
 */
@Entity
@Table(name = "org_quota")
public class OrgQuota {

    @Id
    @Column(name = "org_id")
    private UUID organisationId;

    @Column(name = "max_journals", nullable = false)
    private int maxJournals;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrgQuota() {}

    public OrgQuota(UUID organisationId, int maxJournals, Instant updatedAt) {
        this.organisationId = organisationId;
        this.maxJournals = maxJournals;
        this.updatedAt = updatedAt;
    }

    public UUID getOrganisationId() { return organisationId; }
    public int getMaxJournals() { return maxJournals; }
    public Instant getUpdatedAt() { return updatedAt; }
}
