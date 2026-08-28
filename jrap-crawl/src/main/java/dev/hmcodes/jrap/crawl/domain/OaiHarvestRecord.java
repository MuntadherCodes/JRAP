package dev.hmcodes.jrap.crawl.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** One OAI-PMH record header harvested as a cross-check on the HTML crawl (FR-CRWL-2). */
@Entity
@Table(name = "oai_harvest")
public class OaiHarvestRecord implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Column(nullable = false)
    private String identifier;

    private String datestamp;

    private String title;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OaiHarvestRecord() {}

    public OaiHarvestRecord(UUID id, UUID organisationId, UUID auditId, String identifier,
                            String datestamp, String title, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.auditId = auditId;
        this.identifier = identifier;
        this.datestamp = datestamp;
        this.title = title;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAuditId() { return auditId; }
    public String getIdentifier() { return identifier; }
    public String getDatestamp() { return datestamp; }
    public String getTitle() { return title; }
    public Instant getCreatedAt() { return createdAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
