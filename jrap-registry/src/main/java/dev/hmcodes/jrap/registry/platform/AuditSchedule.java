package dev.hmcodes.jrap.registry.platform;

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
import java.time.Period;
import java.util.UUID;

/** FR-DASH-3: a recurring re-audit schedule with completion/material-change email. */
@Entity
@Table(name = "audit_schedule")
public class AuditSchedule implements Persistable<UUID> {

    public enum Cadence {
        MONTHLY(Period.ofMonths(1)), QUARTERLY(Period.ofMonths(3)),
        SEMIANNUAL(Period.ofMonths(6)), ANNUAL(Period.ofYears(1));

        private final Period period;
        Cadence(Period period) { this.period = period; }
        public Period period() { return period; }
    }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Cadence cadence;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "notify_email", nullable = false)
    private boolean notifyEmail = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "last_audit_id")
    private UUID lastAuditId;

    @Column(name = "last_notified_audit_id")
    private UUID lastNotifiedAuditId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditSchedule() {}

    public AuditSchedule(UUID id, UUID organisationId, UUID journalId, Cadence cadence,
                         Instant nextRunAt, boolean notifyEmail, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.journalId = journalId;
        this.cadence = cadence;
        this.nextRunAt = nextRunAt;
        this.notifyEmail = notifyEmail;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getJournalId() { return journalId; }
    public Cadence getCadence() { return cadence; }
    public Instant getNextRunAt() { return nextRunAt; }
    public boolean isNotifyEmail() { return notifyEmail; }
    public boolean isActive() { return active; }
    public UUID getLastAuditId() { return lastAuditId; }
    public UUID getLastNotifiedAuditId() { return lastNotifiedAuditId; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }

    public void setActive(boolean active) { this.active = active; }
    public void fired(UUID auditId, Instant now) {
        this.lastAuditId = auditId;
        this.nextRunAt = java.time.ZonedDateTime.ofInstant(now, java.time.ZoneOffset.UTC)
                .plus(cadence.period()).toInstant();
    }
    public void notified(UUID auditId) { this.lastNotifiedAuditId = auditId; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
