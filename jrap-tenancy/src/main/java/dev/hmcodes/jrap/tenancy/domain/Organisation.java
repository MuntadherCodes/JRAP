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

/** Tenant: a billing and data-isolation boundary (SRS §1.3, FR-AUTH-1). */
@Entity
@Table(name = "organisation")
public class Organisation implements Persistable<UUID> {

    public enum Status { PENDING_VERIFICATION, ACTIVE, ARCHIVED }

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Organisation() {}

    public Organisation(UUID id, String name, Status status, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setStatus(Status status) { this.status = status; }
    public void setName(String name) { this.name = name; }

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
