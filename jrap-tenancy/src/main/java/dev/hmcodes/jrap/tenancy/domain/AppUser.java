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

/** A user within one organisation, with one role (SRS §2.3, FR-AUTH-1/2). */
@Entity
@Table(name = "app_user")
public class AppUser implements Persistable<UUID> {

    /** Organisation-scoped roles per SRS §2.3. The platform administrator is a separate concept (Phase 8). */
    public enum Role { OWNER, ANALYST, VIEWER }

    public enum Status { INVITED, PENDING_VERIFICATION, ACTIVE, DISABLED }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "totp_secret")
    private String totpSecret;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    protected AppUser() {}

    public AppUser(UUID id, UUID organisationId, String email, String displayName,
                   Role role, Status status, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public Status getStatus() { return status; }
    public String getTotpSecret() { return totpSecret; }
    public boolean isTotpEnabled() { return totpEnabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEmailVerifiedAt() { return emailVerifiedAt; }

    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setRole(Role role) { this.role = role; }
    public void setStatus(Status status) { this.status = status; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }
    public void setEmailVerifiedAt(Instant emailVerifiedAt) { this.emailVerifiedAt = emailVerifiedAt; }

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
