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

/** An extracted article record with script/language detection and provenance (FR-EXT-2/3/4). */
@Entity
@Table(name = "article")
public class Article implements Persistable<UUID> {

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

    private String title;

    @Column(name = "title_script")
    private String titleScript;

    private String doi;
    private String pages;

    @Column(name = "abstract_text")
    private String abstractText;

    @Column(name = "abstract_language")
    private String abstractLanguage;

    @Column(name = "date_submitted")
    private String dateSubmitted;

    @Column(name = "date_accepted")
    private String dateAccepted;

    @Column(name = "date_published")
    private String datePublished;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String keywords = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "references_json", nullable = false)
    private String referencesJson = "[]";

    @Column(name = "references_count", nullable = false)
    private int referencesCount;

    @Column(name = "references_roman_share")
    private BigDecimal referencesRomanShare;

    @Column(nullable = false)
    private String method;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(nullable = false)
    private BigDecimal confidence;

    @Column(name = "needs_review", nullable = false)
    private boolean needsReview;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Article() {}

    public Article(UUID id, UUID organisationId, UUID journalId, UUID auditId, UUID snapshotId,
                   Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.journalId = journalId;
        this.auditId = auditId;
        this.snapshotId = snapshotId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getJournalId() { return journalId; }
    public UUID getAuditId() { return auditId; }
    public UUID getSnapshotId() { return snapshotId; }
    public String getTitle() { return title; }
    public String getTitleScript() { return titleScript; }
    public String getDoi() { return doi; }
    public String getPages() { return pages; }
    public String getAbstractText() { return abstractText; }
    public String getAbstractLanguage() { return abstractLanguage; }
    public String getDateSubmitted() { return dateSubmitted; }
    public String getDateAccepted() { return dateAccepted; }
    public String getDatePublished() { return datePublished; }
    public String getKeywords() { return keywords; }
    public String getReferencesJson() { return referencesJson; }
    public int getReferencesCount() { return referencesCount; }
    public BigDecimal getReferencesRomanShare() { return referencesRomanShare; }
    public String getMethod() { return method; }
    public String getPromptVersion() { return promptVersion; }
    public BigDecimal getConfidence() { return confidence; }
    public boolean isNeedsReview() { return needsReview; }
    public Instant getCreatedAt() { return createdAt; }

    public void setTitle(String title) { this.title = title; }
    public void setTitleScript(String titleScript) { this.titleScript = titleScript; }
    public void setDoi(String doi) { this.doi = doi; }
    public void setPages(String pages) { this.pages = pages; }
    public void setAbstractText(String abstractText) { this.abstractText = abstractText; }
    public void setAbstractLanguage(String abstractLanguage) { this.abstractLanguage = abstractLanguage; }
    public void setDateSubmitted(String dateSubmitted) { this.dateSubmitted = dateSubmitted; }
    public void setDateAccepted(String dateAccepted) { this.dateAccepted = dateAccepted; }
    public void setDatePublished(String datePublished) { this.datePublished = datePublished; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public void setReferencesJson(String referencesJson) { this.referencesJson = referencesJson; }
    public void setReferencesCount(int referencesCount) { this.referencesCount = referencesCount; }
    public void setReferencesRomanShare(BigDecimal share) { this.referencesRomanShare = share; }
    public void setMethod(String method) { this.method = method; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public void setConfidence(BigDecimal confidence) { this.confidence = confidence; }
    public void setNeedsReview(boolean needsReview) { this.needsReview = needsReview; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
