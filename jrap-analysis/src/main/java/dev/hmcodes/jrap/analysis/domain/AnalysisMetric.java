package dev.hmcodes.jrap.analysis.domain;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One computed metric (FR-ANL-2/3/4): numeric value plus a structured detail payload. */
@Entity
@Table(name = "analysis_metric")
public class AnalysisMetric implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "audit_id", nullable = false)
    private UUID auditId;

    @Column(nullable = false)
    private String name;

    private BigDecimal value;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String detail = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AnalysisMetric() {}

    public AnalysisMetric(UUID id, UUID organisationId, UUID auditId, String name,
                          BigDecimal value, String detail, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.auditId = auditId;
        this.name = name;
        this.value = value;
        this.detail = detail == null ? "{}" : detail;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getAuditId() { return auditId; }
    public String getName() { return name; }
    public BigDecimal getValue() { return value; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
