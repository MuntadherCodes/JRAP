package dev.hmcodes.jrap.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One analyst action in the review workflow — the append-only decision history
 * (FR-REV-1: every action logged with user and timestamp). Rows are write-once
 * (DB immutability trigger); the state a decision produces lives on the target row.
 */
@Entity
@Table(name = "review_decision")
public class ReviewDecision implements Persistable<UUID> {

    public enum TargetType { FINDING, BOARD_MEMBER, ARTICLE, EVIDENCE }

    public enum Action { CONFIRM, REJECT, EDIT_SEVERITY, ANNOTATE, EXCLUDE, INCLUDE, CORRECT, ATTACH_EVIDENCE }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value")
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value")
    private String newValue;

    @Column(name = "decided_by", nullable = false)
    private UUID decidedBy;

    @Column(name = "decided_by_email", nullable = false)
    private String decidedByEmail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReviewDecision() {}

    public ReviewDecision(UUID id, UUID organisationId, UUID auditId, TargetType targetType,
                          UUID targetId, Action action, String reason, String oldValue,
                          String newValue, UUID decidedBy, String decidedByEmail, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.auditId = auditId;
        this.targetType = targetType;
        this.targetId = targetId;
        this.action = action;
        this.reason = reason;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.decidedBy = decidedBy;
        this.decidedByEmail = decidedByEmail;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAuditId() { return auditId; }
    public TargetType getTargetType() { return targetType; }
    public UUID getTargetId() { return targetId; }
    public Action getAction() { return action; }
    public String getReason() { return reason; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public UUID getDecidedBy() { return decidedBy; }
    public String getDecidedByEmail() { return decidedByEmail; }
    public Instant getCreatedAt() { return createdAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
