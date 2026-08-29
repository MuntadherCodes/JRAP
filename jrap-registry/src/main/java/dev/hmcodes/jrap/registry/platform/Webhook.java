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

/** A per-organisation webhook endpoint (§3.2.2): audit.completed, finding.critical. */
@Entity
@Table(name = "webhook")
public class Webhook implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String secret;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String events = "[\"audit.completed\"]";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_status")
    private Integer lastStatus;

    @Column(name = "last_delivery_at")
    private Instant lastDeliveryAt;

    protected Webhook() {}

    public Webhook(UUID id, UUID organisationId, String url, String secret, String events,
                   Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.url = url;
        this.secret = secret;
        this.events = events;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getUrl() { return url; }
    public String getSecret() { return secret; }
    public String getEvents() { return events; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Integer getLastStatus() { return lastStatus; }
    public Instant getLastDeliveryAt() { return lastDeliveryAt; }

    public void setActive(boolean active) { this.active = active; }
    public void recordDelivery(Integer status, Instant when) {
        this.lastStatus = status;
        this.lastDeliveryAt = when;
    }
    public boolean subscribes(String event) {
        return events != null && events.contains("\"" + event + "\"");
    }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
