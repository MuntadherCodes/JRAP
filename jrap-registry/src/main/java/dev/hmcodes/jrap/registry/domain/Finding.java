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
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * A machine- or analyst-generated statement about a journal, referencing evidence and
 * carrying a severity (SRS §1.3; envelope per FR-ANL-12). Phase 2 produces category
 * "identity" findings; the audit linkage joins in Phase 3.
 */
@Entity
@Table(name = "finding")
public class Finding implements Persistable<UUID> {

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

    public enum Status { AUTO, CONFIRMED, REJECTED, NEEDS_VERIFICATION }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(name = "detector_version", nullable = false)
    private String detectorVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Finding() {}

    public Finding(UUID id, UUID organisationId, UUID journalId, String category, String code,
                   Severity severity, Status status, String title, String description,
                   String detectorVersion, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.journalId = journalId;
        this.category = category;
        this.code = code;
        this.severity = severity;
        this.status = status;
        this.title = title;
        this.description = description;
        this.detectorVersion = detectorVersion;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getJournalId() { return journalId; }
    public String getCategory() { return category; }
    public String getCode() { return code; }
    public Severity getSeverity() { return severity; }
    public Status getStatus() { return status; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getDetectorVersion() { return detectorVersion; }
    public Instant getCreatedAt() { return createdAt; }

    public void setSeverity(Severity severity) { this.severity = severity; }
    public void setStatus(Status status) { this.status = status; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
