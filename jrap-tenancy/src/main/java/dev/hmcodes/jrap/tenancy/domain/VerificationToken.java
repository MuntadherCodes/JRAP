package dev.hmcodes.jrap.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** Single-use token for email verification and invitations (FR-AUTH-1). Stored hashed. */
@Entity
@Table(name = "verification_token")
public class VerificationToken implements Persistable<UUID> {

    public enum Purpose { VERIFY_EMAIL, INVITE }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "org_id", nullable = false)
    private UUID organisationId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected VerificationToken() {}

    public VerificationToken(UUID id, UUID userId, UUID organisationId, String tokenHash,
                             Purpose purpose, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.organisationId = organisationId;
        this.tokenHash = tokenHash;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getOrganisationId() { return organisationId; }
    public String getTokenHash() { return tokenHash; }
    public Purpose getPurpose() { return purpose; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }
    public void markUsed(Instant when) { this.usedAt = when; }

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
