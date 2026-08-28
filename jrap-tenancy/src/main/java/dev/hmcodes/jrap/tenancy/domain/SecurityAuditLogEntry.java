package dev.hmcodes.jrap.tenancy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit log of security-relevant events (FR-AUTH-5).
 * Immutability is enforced in the database (trigger rejects UPDATE/DELETE);
 * retention is >= 2 years — no purge job exists for this table.
 */
@Entity
@Table(name = "security_audit_log")
public class SecurityAuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "org_id")
    private UUID organisationId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String details;

    @Column(name = "source_ip")
    private String sourceIp;

    protected SecurityAuditLogEntry() {}

    public SecurityAuditLogEntry(Instant occurredAt, UUID organisationId, UUID actorUserId,
                                 String actorEmail, String eventType, String details, String sourceIp) {
        this.occurredAt = occurredAt;
        this.organisationId = organisationId;
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
        this.eventType = eventType;
        this.details = details;
        this.sourceIp = sourceIp;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getActorUserId() { return actorUserId; }
    public String getActorEmail() { return actorEmail; }
    public String getEventType() { return eventType; }
    public String getDetails() { return details; }
    public String getSourceIp() { return sourceIp; }
}
