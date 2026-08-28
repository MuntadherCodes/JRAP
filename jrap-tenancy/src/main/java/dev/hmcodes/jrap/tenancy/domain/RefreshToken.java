package dev.hmcodes.jrap.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** Opaque refresh token with rotation and reuse detection (FR-AUTH-2). Stored hashed. */
@Entity
@Table(name = "refresh_token")
public class RefreshToken implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "org_id", nullable = false)
    private UUID organisationId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    protected RefreshToken() {}

    public RefreshToken(UUID id, UUID userId, UUID organisationId, String tokenHash,
                        Instant issuedAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.organisationId = organisationId;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getOrganisationId() { return organisationId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public UUID getReplacedBy() { return replacedBy; }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(Instant when, UUID replacedBy) {
        this.revokedAt = when;
        this.replacedBy = replacedBy;
    }

    @Transient
    private boolean isNew = true;

    /** Assigned-UUID entities: tell Spring Data this is an insert, avoiding a merge SELECT. */
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
