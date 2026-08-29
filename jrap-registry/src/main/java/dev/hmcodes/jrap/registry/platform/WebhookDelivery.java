package dev.hmcodes.jrap.registry.platform;

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

/** One webhook delivery attempt with its outcome — the observable delivery log. */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "webhook_id", nullable = false)
    private UUID webhookId;

    @Column(nullable = false)
    private String event;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(nullable = false)
    private boolean ok;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    protected WebhookDelivery() {}

    public WebhookDelivery(UUID id, UUID organisationId, UUID webhookId, String event,
                           String payload, Integer statusCode, boolean ok, Instant attemptedAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.webhookId = webhookId;
        this.event = event;
        this.payload = payload;
        this.statusCode = statusCode;
        this.ok = ok;
        this.attemptedAt = attemptedAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getWebhookId() { return webhookId; }
    public String getEvent() { return event; }
    public String getPayload() { return payload; }
    public Integer getStatusCode() { return statusCode; }
    public boolean isOk() { return ok; }
    public Instant getAttemptedAt() { return attemptedAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
