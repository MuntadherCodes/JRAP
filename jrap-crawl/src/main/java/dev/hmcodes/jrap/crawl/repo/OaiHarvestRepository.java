package dev.hmcodes.jrap.crawl.repo;

import dev.hmcodes.jrap.crawl.domain.OaiHarvestRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface OaiHarvestRepository extends JpaRepository<OaiHarvestRecord, UUID> {

    @Transactional(readOnly = true)
    long countByAuditId(UUID auditId);

    @Transactional(readOnly = true)
    boolean existsByAuditIdAndIdentifier(UUID auditId, String identifier);
}
