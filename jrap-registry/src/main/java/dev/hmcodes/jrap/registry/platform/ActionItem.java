package dev.hmcodes.jrap.registry.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A tracked remediation action (FR-DASH-1/2): adopted from a report's roadmap,
 * assignable with a due date; completion (with note/evidence) feeds the next
 * audit's delta narrative.
 */
@Entity
@Table(name = "action_item")
public class ActionItem implements Persistable<UUID> {

    public enum Status { OPEN, IN_PROGRESS, DONE }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(name = "report_id")
    private UUID reportId;

    @Column(name = "catalogue_action_id", nullable = false)
    private String catalogueActionId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String phase;

    @Column(nullable = false)
    private String tag;

    @Column(name = "completion_criterion", nullable = false)
    private String completionCriterion;

    @Column(name = "assignee_user_id")
    private UUID assigneeUserId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "completion_note")
    private String completionNote;

    @Column(name = "completion_evidence_id")
    private UUID completionEvidenceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ActionItem() {}

    public ActionItem(UUID id, UUID organisationId, UUID journalId, UUID reportId,
                      String catalogueActionId, String title, String description, String phase,
                      String tag, String completionCriterion, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.journalId = journalId;
        this.reportId = reportId;
        this.catalogueActionId = catalogueActionId;
        this.title = title;
        this.description = description;
        this.phase = phase;
        this.tag = tag;
        this.completionCriterion = completionCriterion;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getJournalId() { return journalId; }
    public UUID getReportId() { return reportId; }
    public String getCatalogueActionId() { return catalogueActionId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getPhase() { return phase; }
    public String getTag() { return tag; }
    public String getCompletionCriterion() { return completionCriterion; }
    public UUID getAssigneeUserId() { return assigneeUserId; }
    public LocalDate getDueDate() { return dueDate; }
    public Status getStatus() { return status; }
    public String getCompletionNote() { return completionNote; }
    public UUID getCompletionEvidenceId() { return completionEvidenceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void assign(UUID userId, LocalDate dueDate, Instant when) {
        this.assigneeUserId = userId;
        this.dueDate = dueDate;
        this.updatedAt = when;
    }

    public void setStatus(Status status, String note, UUID evidenceId, Instant when) {
        this.status = status;
        this.updatedAt = when;
        if (status == Status.DONE) {
            this.completionNote = note;
            this.completionEvidenceId = evidenceId;
            this.completedAt = when;
        }
    }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
