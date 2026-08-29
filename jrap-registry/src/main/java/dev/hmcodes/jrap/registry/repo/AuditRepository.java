package dev.hmcodes.jrap.registry.repo;

import dev.hmcodes.jrap.registry.domain.Audit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditRepository extends JpaRepository<Audit, UUID> {

    // Derived queries carry @Transactional so the RLS GUCs are set even when called
    // outside a service transaction (standing rule since Phase 2).

    @Transactional(readOnly = true)
    List<Audit> findByJournalIdOrderByCreatedAtDesc(UUID journalId);

    @Transactional(readOnly = true)
    Optional<Audit> findFirstByStatusOrderByCreatedAt(Audit.Status status);

    @Transactional(readOnly = true)
    boolean existsByJournalIdAndStatusIn(UUID journalId, List<Audit.Status> statuses);

    @Transactional(readOnly = true)
    Optional<Audit> findFirstByJournalIdOrderByCreatedAtDesc(UUID journalId);

    @Transactional(readOnly = true)
    List<Audit> findByStatusAndFinishedAtAfter(Audit.Status status, java.time.Instant after);
}
