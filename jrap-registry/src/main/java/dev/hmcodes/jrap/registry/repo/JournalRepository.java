package dev.hmcodes.jrap.registry.repo;

import dev.hmcodes.jrap.registry.domain.Journal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalRepository extends JpaRepository<Journal, UUID> {

    List<Journal> findByOrganisationIdOrderByCreatedAtDesc(UUID organisationId);

    /**
     * NOTE: derived query methods get NO transaction by default (unlike the CRUD methods
     * inherited from SimpleJpaRepository). Without a transaction the tenant GUCs are never
     * set and row-level security hides every row — so any derived query that may be called
     * outside a service transaction MUST carry @Transactional itself.
     */
    @Transactional(readOnly = true)
    long countByOrganisationIdAndStatus(UUID organisationId, Journal.Status status);

    @Transactional(readOnly = true)
    Optional<Journal> findByOrganisationIdAndIssnLAndStatus(UUID organisationId, String issnL, Journal.Status status);
}
