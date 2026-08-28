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

/** One CSAB category score 0-5 with the rubric criteria met and missed (FR-ANL-5, §5.2). */
@Entity
@Table(name = "csab_score")
public class CsabScore implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private int score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String criteria = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CsabScore() {}

    public CsabScore(UUID id, UUID organisationId, UUID auditId, UUID journalId, String category,
                     int score, String criteria, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.auditId = auditId;
        this.journalId = journalId;
        this.category = category;
        this.score = score;
        this.criteria = criteria == null ? "[]" : criteria;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAuditId() { return auditId; }
    public UUID getJournalId() { return journalId; }
    public String getCategory() { return category; }
    public int getScore() { return score; }
    public String getCriteria() { return criteria; }
    public Instant getCreatedAt() { return createdAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
