package dev.hmcodes.jrap.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * A versioned audit report (FR-RPT-1). Content is structured sentences with citations
 * (CON-5); drafts are editable, RELEASED rows are frozen by DB trigger and hash-stamped
 * (FR-RPT-5). The FR-RPT-4 guard verdict is stored alongside the content it judged.
 */
@Entity
@Table(name = "report")
public class Report implements Persistable<UUID> {

    public enum Status { DRAFT, RELEASED }

    public enum Verdict { READY, CONDITIONAL, NOT_READY }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Verdict verdict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String sections = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String roadmap = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "guard_report", nullable = false)
    private String guardReport = "{}";

    @Column(name = "guard_passed", nullable = false)
    private boolean guardPassed;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String exclusions = "[]";

    @Column(name = "narrative_prompt_version")
    private String narrativePromptVersion;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected Report() {}

    public Report(UUID id, UUID organisationId, UUID auditId, UUID journalId, int version,
                  Verdict verdict, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.auditId = auditId;
        this.journalId = journalId;
        this.version = version;
        this.status = Status.DRAFT;
        this.verdict = verdict;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAuditId() { return auditId; }
    public UUID getJournalId() { return journalId; }
    public int getVersion() { return version; }
    public Status getStatus() { return status; }
    public Verdict getVerdict() { return verdict; }
    public String getSections() { return sections; }
    public String getRoadmap() { return roadmap; }
    public String getGuardReport() { return guardReport; }
    public boolean isGuardPassed() { return guardPassed; }
    public String getExclusions() { return exclusions; }
    public String getNarrativePromptVersion() { return narrativePromptVersion; }
    public String getContentHash() { return contentHash; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getReleasedBy() { return releasedBy; }
    public Instant getReleasedAt() { return releasedAt; }

    public void setVerdict(Verdict verdict) { this.verdict = verdict; }
    public void setSections(String sections) { this.sections = sections; }
    public void setRoadmap(String roadmap) { this.roadmap = roadmap; }
    public void setGuardReport(String guardReport, boolean passed) {
        this.guardReport = guardReport;
        this.guardPassed = passed;
    }
    public void setExclusions(String exclusions) { this.exclusions = exclusions; }
    public void setNarrativePromptVersion(String version) { this.narrativePromptVersion = version; }

    public void release(String contentHash, UUID releasedBy, Instant when) {
        this.status = Status.RELEASED;
        this.contentHash = contentHash;
        this.releasedBy = releasedBy;
        this.releasedAt = when;
    }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
