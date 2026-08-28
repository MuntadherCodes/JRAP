package dev.hmcodes.jrap.registry.repo;

import dev.hmcodes.jrap.registry.domain.Finding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

    @Transactional(readOnly = true)
    List<Finding> findByJournalId(UUID journalId);

    @Transactional(readOnly = true)
    boolean existsByJournalIdAndCode(UUID journalId, String code);

    @Transactional(readOnly = true)
    boolean existsByAuditIdAndCategory(UUID auditId, String category);

    @Transactional(readOnly = true)
    List<Finding> findByAuditId(UUID auditId);
}
