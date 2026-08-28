package dev.hmcodes.jrap.registry.domain;

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

/** A registered journal with its resolved cross-source identity (FR-JRN-1). */
@Entity
@Table(name = "journal")
public class Journal implements Persistable<UUID> {

    public enum Status { ACTIVE, ARCHIVED }

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "registered_input", nullable = false)
    private String registeredInput;

    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "title_variants", nullable = false)
    private String titleVariants = "[]";

    private String publisher;
    private String country;

    @Column(name = "issn_l")
    private String issnL;

    @Column(name = "issn_print")
    private String issnPrint;

    @Column(name = "issn_online")
    private String issnOnline;

    @Column(name = "doi_prefix")
    private String doiPrefix;

    private String platform;

    @Column(name = "homepage_url")
    private String homepageUrl;

    @Column(name = "openalex_id")
    private String openalexId;

    @Column(name = "doaj_id")
    private String doajId;

    @Column(name = "in_crossref", nullable = false)
    private boolean inCrossref;

    @Column(name = "in_doaj", nullable = false)
    private boolean inDoaj;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Journal() {}

    public Journal(UUID id, UUID organisationId, String registeredInput, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.registeredInput = registeredInput;
        this.status = Status.ACTIVE;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public Status getStatus() { return status; }
    public String getRegisteredInput() { return registeredInput; }
    public String getTitle() { return title; }
    public String getTitleVariants() { return titleVariants; }
    public String getPublisher() { return publisher; }
    public String getCountry() { return country; }
    public String getIssnL() { return issnL; }
    public String getIssnPrint() { return issnPrint; }
    public String getIssnOnline() { return issnOnline; }
    public String getDoiPrefix() { return doiPrefix; }
    public String getPlatform() { return platform; }
    public String getHomepageUrl() { return homepageUrl; }
    public String getOpenalexId() { return openalexId; }
    public String getDoajId() { return doajId; }
    public boolean isInCrossref() { return inCrossref; }
    public boolean isInDoaj() { return inDoaj; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getArchivedAt() { return archivedAt; }

    public void setTitle(String title) { this.title = title; }
    public void setTitleVariants(String titleVariants) { this.titleVariants = titleVariants; }
    public void setPublisher(String publisher) { this.publisher = publisher; }
    public void setCountry(String country) { this.country = country; }
    public void setIssnL(String issnL) { this.issnL = issnL; }
    public void setIssnPrint(String issnPrint) { this.issnPrint = issnPrint; }
    public void setIssnOnline(String issnOnline) { this.issnOnline = issnOnline; }
    public void setDoiPrefix(String doiPrefix) { this.doiPrefix = doiPrefix; }
    public void setPlatform(String platform) { this.platform = platform; }
    public void setHomepageUrl(String homepageUrl) { this.homepageUrl = homepageUrl; }
    public void setOpenalexId(String openalexId) { this.openalexId = openalexId; }
    public void setDoajId(String doajId) { this.doajId = doajId; }
    public void setInCrossref(boolean inCrossref) { this.inCrossref = inCrossref; }
    public void setInDoaj(boolean inDoaj) { this.inDoaj = inDoaj; }

    public void archive(Instant when) {
        this.status = Status.ARCHIVED;
        this.archivedAt = when;
    }

    public void unarchive() {
        this.status = Status.ACTIVE;
        this.archivedAt = null;
    }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
