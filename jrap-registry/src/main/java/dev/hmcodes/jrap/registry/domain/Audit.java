package dev.hmcodes.jrap.registry.domain;

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
 * One complete evaluation run of one journal at a point in time (SRS §1.3).
 * The pipeline is checkpointed per stage; an interrupted audit resumes from its last
 * completed stage, never restarts from zero (SRS §4, NFR-AVL-1). Phase 3 implements
 * the CRAWL stage; later phases append EXTRACT → ENRICH → ANALYSE → ...
 */
@Entity
@Table(name = "audit")
public class Audit implements Persistable<UUID> {

    public enum Status { PENDING, RUNNING, COMPLETE, FAILED, CANCELLED }

    /** Pipeline stages in order (SRS §4). Phase 3 stops after CRAWL. */
    public enum Stage { CRAWL, EXTRACT, ENRICH, ANALYSE, REVIEW, DRAFT, GUARD, RELEASE }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Stage stage;

    @Column(name = "page_cap", nullable = false)
    private int pageCap;

    @Column(name = "pages_fetched", nullable = false)
    private int pagesFetched;

    @Column(name = "pages_skipped", nullable = false)
    private int pagesSkipped;

    @Column(name = "articles_extracted", nullable = false)
    private int articlesExtracted;

    @Column(name = "board_members_extracted", nullable = false)
    private int boardMembersExtracted;

    private String error;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "rubric_version")
    private String rubricVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detector_versions", nullable = false)
    private String detectorVersions = "{}";

    protected Audit() {}

    public Audit(UUID id, UUID organisationId, UUID journalId, int pageCap, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.journalId = journalId;
        this.status = Status.PENDING;
        this.stage = Stage.CRAWL;
        this.pageCap = pageCap;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getJournalId() { return journalId; }
    public Status getStatus() { return status; }
    public Stage getStage() { return stage; }
    public int getPageCap() { return pageCap; }
    public int getPagesFetched() { return pagesFetched; }
    public int getPagesSkipped() { return pagesSkipped; }
    public int getArticlesExtracted() { return articlesExtracted; }
    public int getBoardMembersExtracted() { return boardMembersExtracted; }
    public String getError() { return error; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getRubricVersion() { return rubricVersion; }
    public String getDetectorVersions() { return detectorVersions; }

    public void markRunning(Instant when) {
        this.status = Status.RUNNING;
        if (this.startedAt == null) {
            this.startedAt = when;
        }
    }

    public void markComplete(Instant when) {
        this.status = Status.COMPLETE;
        this.finishedAt = when;
    }

    public void markFailed(String error, Instant when) {
        this.status = Status.FAILED;
        this.error = error;
        this.finishedAt = when;
    }

    public void markCancelled(Instant when) {
        this.status = Status.CANCELLED;
        this.finishedAt = when;
    }

    public void setStage(Stage stage) { this.stage = stage; }

    /** §3.3: an audit freezes the rubric and detector versions it used. */
    public void freezeVersions(String rubricVersion, String detectorVersionsJson) {
        this.rubricVersion = rubricVersion;
        this.detectorVersions = detectorVersionsJson == null ? "{}" : detectorVersionsJson;
    }
    public void setPagesFetched(int pagesFetched) { this.pagesFetched = pagesFetched; }
    public void setPagesSkipped(int pagesSkipped) { this.pagesSkipped = pagesSkipped; }
    public void setArticlesExtracted(int articlesExtracted) { this.articlesExtracted = articlesExtracted; }
    public void setBoardMembersExtracted(int boardMembersExtracted) { this.boardMembersExtracted = boardMembersExtracted; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
