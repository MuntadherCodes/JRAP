package dev.hmcodes.jrap.registry.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** A global platform setting (FR-ADM-1): feature flags, crawl blocklist, rubric override. */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "key")
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String value;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AppSetting() {}

    public AppSetting(String key, String value, UUID updatedBy, Instant updatedAt) {
        this.key = key;
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public UUID getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String value, UUID updatedBy, Instant when) {
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = when;
    }
}
