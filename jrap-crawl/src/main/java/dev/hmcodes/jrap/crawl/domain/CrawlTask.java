package dev.hmcodes.jrap.crawl.domain;

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
 * One URL in an audit's crawl frontier. The frontier is persisted so an interrupted
 * crawl resumes exactly where it stopped (NFR-AVL-1); every skipped or blocked URL
 * records its reason (FR-CRWL-4 — blocked pages become findings, never silent gaps).
 */
@Entity
@Table(name = "crawl_task")
public class CrawlTask implements Persistable<UUID> {

    public enum Status { QUEUED, DONE, SKIPPED, FAILED }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Column(nullable = false)
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "skip_reason")
    private String skipReason;

    @Column(nullable = false)
    private int depth;

    @Column(name = "discovered_from")
    private String discoveredFrom;

    @Column(name = "fetched_at")
    private Instant fetchedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CrawlTask() {}

    public CrawlTask(UUID id, UUID organisationId, UUID auditId, String url, int depth,
                     String discoveredFrom, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.auditId = auditId;
        this.url = url;
        this.status = Status.QUEUED;
        this.depth = depth;
        this.discoveredFrom = discoveredFrom;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAuditId() { return auditId; }
    public String getUrl() { return url; }
    public Status getStatus() { return status; }
    public String getSkipReason() { return skipReason; }
    public int getDepth() { return depth; }
    public String getDiscoveredFrom() { return discoveredFrom; }
    public Instant getFetchedAt() { return fetchedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void markDone(Instant when) {
        this.status = Status.DONE;
        this.fetchedAt = when;
    }

    public void markSkipped(String reason, Instant when) {
        this.status = Status.SKIPPED;
        this.skipReason = reason;
        this.fetchedAt = when;
    }

    public void markFailed(String reason, Instant when) {
        this.status = Status.FAILED;
        this.skipReason = reason;
        this.fetchedAt = when;
    }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
