package dev.hmcodes.jrap.registry.domain;

import dev.hmcodes.jrap.integrations.dto.SourceAvailability;
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

/** The journal's identity as ONE source stated it at resolution time (FR-JRN-1/2). */
@Entity
@Table(name = "journal_identity_record")
public class JournalIdentityRecord implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(nullable = false)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceAvailability availability;

    @Column(name = "api_record_id")
    private UUID apiRecordId;

    private String title;
    private String publisher;
    private String country;

    @Column(name = "issn_print")
    private String issnPrint;

    @Column(name = "issn_online")
    private String issnOnline;

    @Column(name = "issn_l")
    private String issnL;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String extra = "{}";

    @Column(name = "retrieved_at", nullable = false)
    private Instant retrievedAt;

    protected JournalIdentityRecord() {}

    public JournalIdentityRecord(UUID id, UUID organisationId, UUID journalId, String source,
                                 SourceAvailability availability, UUID apiRecordId, Instant retrievedAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.journalId = journalId;
        this.source = source;
        this.availability = availability;
        this.apiRecordId = apiRecordId;
        this.retrievedAt = retrievedAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getJournalId() { return journalId; }
    public String getSource() { return source; }
    public SourceAvailability getAvailability() { return availability; }
    public UUID getApiRecordId() { return apiRecordId; }
    public String getTitle() { return title; }
    public String getPublisher() { return publisher; }
    public String getCountry() { return country; }
    public String getIssnPrint() { return issnPrint; }
    public String getIssnOnline() { return issnOnline; }
    public String getIssnL() { return issnL; }
    public String getExtra() { return extra; }
    public Instant getRetrievedAt() { return retrievedAt; }

    public void setTitle(String title) { this.title = title; }
    public void setPublisher(String publisher) { this.publisher = publisher; }
    public void setCountry(String country) { this.country = country; }
    public void setIssnPrint(String issnPrint) { this.issnPrint = issnPrint; }
    public void setIssnOnline(String issnOnline) { this.issnOnline = issnOnline; }
    public void setIssnL(String issnL) { this.issnL = issnL; }
    public void setExtra(String extra) { this.extra = extra; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
