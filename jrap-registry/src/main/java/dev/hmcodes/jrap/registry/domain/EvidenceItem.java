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
 * A stored, timestamped artefact backing findings (SRS §1.3, CON-5). Phase 2 evidence
 * is API_RECORD-typed and points into the immutable api_record store; SNAPSHOT joins in
 * Phase 3, MANUAL in Phase 6, COMPUTED in Phase 5.
 */
@Entity
@Table(name = "evidence_item")
public class EvidenceItem implements Persistable<UUID> {

    public enum Type { API_RECORD, SNAPSHOT, MANUAL, COMPUTED }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(name = "api_record_id")
    private UUID apiRecordId;

    @Column(nullable = false)
    private String source;

    private String excerpt;

    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EvidenceItem() {}

    public EvidenceItem(UUID id, UUID organisationId, UUID journalId, Type type, UUID apiRecordId,
                        String source, String excerpt, Instant retrievedAt, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.journalId = journalId;
        this.type = type;
        this.apiRecordId = apiRecordId;
        this.source = source;
        this.excerpt = excerpt;
        this.retrievedAt = retrievedAt;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getJournalId() { return journalId; }
    public Type getType() { return type; }
    public UUID getApiRecordId() { return apiRecordId; }
    public String getSource() { return source; }
    public String getExcerpt() { return excerpt; }
    public Instant getRetrievedAt() { return retrievedAt; }
    public Instant getCreatedAt() { return createdAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
