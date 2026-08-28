package dev.hmcodes.jrap.integrations.cache;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Id;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Write-once cached response from a scholarly source (SRS §3.3 ApiRecord, CON-3).
 * Global (not tenant-scoped): it holds public API data shared by all tenants.
 * Every fetch appends a new record; nothing is ever updated (DB trigger enforces).
 */
@Entity
@Table(name = "api_record")
public class ApiRecord implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String source;

    @Column(name = "request_key", nullable = false)
    private String requestKey;

    @Column(name = "request_url", nullable = false)
    private String requestUrl;

    @Column(name = "status_code", nullable = false)
    private int statusCode;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ApiRecord() {}

    public ApiRecord(UUID id, String source, String requestKey, String requestUrl, int statusCode,
                     String responseBody, String contentHash, Instant retrievedAt, Instant expiresAt) {
        this.id = id;
        this.source = source;
        this.requestKey = requestKey;
        this.requestUrl = requestUrl;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.contentHash = contentHash;
        this.retrievedAt = retrievedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public String getSource() { return source; }
    public String getRequestKey() { return requestKey; }
    public String getRequestUrl() { return requestUrl; }
    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
    public String getContentHash() { return contentHash; }
    public Instant getRetrievedAt() { return retrievedAt; }
    public Instant getExpiresAt() { return expiresAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
