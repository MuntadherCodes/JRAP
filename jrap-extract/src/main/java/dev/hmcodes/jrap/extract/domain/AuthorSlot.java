package dev.hmcodes.jrap.extract.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/** One author position on one article, with normalised identity (FR-EXT-2/6). */
@Entity
@Table(name = "author_slot")
public class AuthorSlot implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    @Column(name = "article_id", nullable = false)
    private UUID articleId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String name;

    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    private String affiliation;
    private String country;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuthorSlot() {}

    public AuthorSlot(UUID id, UUID organisationId, UUID articleId, int position, String name,
                      String normalizedName, String affiliation, String country, Instant createdAt) {
        this.id = id;
        this.organisationId = organisationId;
        this.articleId = articleId;
        this.position = position;
        this.name = name;
        this.normalizedName = normalizedName;
        this.affiliation = affiliation;
        this.country = country;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getArticleId() { return articleId; }
    public int getPosition() { return position; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public String getAffiliation() { return affiliation; }
    public String getCountry() { return country; }
    public Instant getCreatedAt() { return createdAt; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
