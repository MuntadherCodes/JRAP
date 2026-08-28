package dev.hmcodes.jrap.registry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** finding ↔ evidence many-to-many (SRS §3.3). */
@Entity
@Table(name = "evidence_link")
public class EvidenceLink implements Persistable<EvidenceLink.Key> {

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "finding_id")
        private UUID findingId;

        @Column(name = "evidence_item_id")
        private UUID evidenceItemId;

        protected Key() {}

        public Key(UUID findingId, UUID evidenceItemId) {
            this.findingId = findingId;
            this.evidenceItemId = evidenceItemId;
        }

        public UUID getFindingId() { return findingId; }
        public UUID getEvidenceItemId() { return evidenceItemId; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(findingId, other.findingId)
                    && Objects.equals(evidenceItemId, other.evidenceItemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(findingId, evidenceItemId);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID organisationId;

    protected EvidenceLink() {}

    public EvidenceLink(UUID findingId, UUID evidenceItemId, UUID organisationId) {
        this.id = new Key(findingId, evidenceItemId);
        this.organisationId = organisationId;
    }

    public Key getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }
}
