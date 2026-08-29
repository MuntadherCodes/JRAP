package dev.hmcodes.jrap.tenancy.domain;

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
 * A per-organisation API key for the public REST API (FR-AUTH-4): scoped permissions,
 * per-key rate limit, secret stored only as a SHA-256 hash — the full key is shown
 * exactly once at creation.
 */
@Entity
@Table(name = "api_key")
public class ApiKey implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String prefix;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String scopes = "[\"read\"]";

    @Column(name = "rate_limit_per_minute", nullable = false)
    private int rateLimitPerMinute;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKey() {}

    public ApiKey(UUID id, UUID organisationId, String name, String prefix, String keyHash,
                  String scopes, int rateLimitPerMinute, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.prefix = prefix;
        this.keyHash = keyHash;
        this.scopes = scopes;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public String getPrefix() { return prefix; }
    public String getKeyHash() { return keyHash; }
    public String getScopes() { return scopes; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public boolean isRevoked() { return revokedAt != null; }
    public void revoke(Instant when) { this.revokedAt = when; }
    public void touch(Instant when) { this.lastUsedAt = when; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
