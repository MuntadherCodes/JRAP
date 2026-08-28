package dev.hmcodes.jrap.analysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** One gateway check outcome (FR-ANL-1, §5.1): G1..G6, evidence-linked. */
@Entity
@Table(name = "gateway_check")
public class GatewayCheck implements Persistable<UUID> {

    public enum Outcome { PASS, PASS_WITH_CAVEATS, FAIL, UNCLEAR }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String outcome;

    @Column(nullable = false)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_item_ids", nullable = false)
    private String evidenceItemIds = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GatewayCheck() {}

    public GatewayCheck(UUID id, UUID organisationId, UUID auditId, UUID journalId, String code,
                        Outcome outcome, String summary, String evidenceItemIds, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.auditId = auditId;
        this.journalId = journalId;
        this.code = code;
        this.outcome = outcome.name();
        this.summary = summary;
        this.evidenceItemIds = evidenceItemIds == null ? "[]" : evidenceItemIds;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAuditId() { return auditId; }
    public UUID getJournalId() { return journalId; }
    public String getCode() { return code; }
    public String getOutcome() { return outcome; }
    public String getSummary() { return summary; }
    public String getEvidenceItemIds() { return evidenceItemIds; }
    public Instant getCreatedAt() { return createdAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
