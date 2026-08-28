package dev.hmcodes.jrap.extract.domain;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** An extracted editorial-board member with full provenance (FR-EXT-1/4). */
@Entity
@Table(name = "board_member")
public class BoardMember implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Column(name = "snapshot_id", nullable = false)
    private UUID snapshotId;

    @Column(nullable = false)
    private String name;

    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    private String role;
    private String institution;
    private String country;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile_links", nullable = false)
    private String profileLinks = "[]";

    @Column(nullable = false)
    private String method;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(nullable = false)
    private BigDecimal confidence;

    private String excerpt;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BoardMember() {}

    public BoardMember(UUID id, UUID organisationId, UUID journalId, UUID auditId, UUID snapshotId,
                       String name, String normalizedName, String role, String institution,
                       String country, String profileLinks, String method, String promptVersion,
                       BigDecimal confidence, String excerpt, boolean needsReview, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.journalId = journalId;
        this.auditId = auditId;
        this.snapshotId = snapshotId;
        this.name = name;
        this.normalizedName = normalizedName;
        this.role = role;
        this.institution = institution;
        this.country = country;
        this.profileLinks = profileLinks == null ? "[]" : profileLinks;
        this.method = method;
        this.promptVersion = promptVersion;
        this.confidence = confidence;
        this.excerpt = excerpt;
        this.needsReview = needsReview;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getJournalId() { return journalId; }
    public UUID getAuditId() { return auditId; }
    public UUID getSnapshotId() { return snapshotId; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public String getRole() { return role; }
    public String getInstitution() { return institution; }
    public String getCountry() { return country; }
    public String getProfileLinks() { return profileLinks; }
    public String getMethod() { return method; }
    public String getPromptVersion() { return promptVersion; }
    public BigDecimal getConfidence() { return confidence; }
    public String getExcerpt() { return excerpt; }
    public boolean isNeedsReview() { return needsReview; }
    public Instant getCreatedAt() { return createdAt; }

    // Analyst corrections (FR-REV-2); normalizedName must be recomputed alongside name.
    public void setName(String name) { this.name = name; }
    public void setNormalizedName(String normalizedName) { this.normalizedName = normalizedName; }
    public void setRole(String role) { this.role = role; }
    public void setInstitution(String institution) { this.institution = institution; }
    public void setCountry(String country) { this.country = country; }
    public void setNeedsReview(boolean needsReview) { this.needsReview = needsReview; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
