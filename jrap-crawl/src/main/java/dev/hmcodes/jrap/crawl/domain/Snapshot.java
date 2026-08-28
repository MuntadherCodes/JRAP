package dev.hmcodes.jrap.crawl.domain;

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

/**
 * Immutable snapshot of one fetched resource (FR-CRWL-3): metadata here, raw bytes and
 * normalised text in the snapshot store. All downstream extraction references snapshots,
 * never the live web. Write-once — the database trigger rejects UPDATE/DELETE.
 */
@Entity
@Table(name = "snapshot")
public class Snapshot implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(nullable = false)
    private String url;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "raw_storage_key", nullable = false)
    private String rawStorageKey;

    @Column(name = "text_storage_key")
    private String textStorageKey;

    @Column(name = "page_type", nullable = false)
    private String pageType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String headers = "{}";

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Snapshot() {}

    public Snapshot(UUID id, UUID organisationId, UUID auditId, UUID journalId, String url,
                    int httpStatus, String contentType, String contentHash, String rawStorageKey,
                    String textStorageKey, String pageType, String headers,
                    Instant fetchedAt, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.auditId = auditId;
        this.journalId = journalId;
        this.url = url;
        this.httpStatus = httpStatus;
        this.contentType = contentType;
        this.contentHash = contentHash;
        this.rawStorageKey = rawStorageKey;
        this.textStorageKey = textStorageKey;
        this.pageType = pageType;
        this.headers = headers == null ? "{}" : headers;
        this.fetchedAt = fetchedAt;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAuditId() { return auditId; }
    public UUID getJournalId() { return journalId; }
    public String getUrl() { return url; }
    public int getHttpStatus() { return httpStatus; }
    public String getContentType() { return contentType; }
    public String getContentHash() { return contentHash; }
    public String getRawStorageKey() { return rawStorageKey; }
    public String getTextStorageKey() { return textStorageKey; }
    public String getPageType() { return pageType; }
    public String getHeaders() { return headers; }
    public Instant getFetchedAt() { return fetchedAt; }
    public Instant getCreatedAt() { return createdAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
