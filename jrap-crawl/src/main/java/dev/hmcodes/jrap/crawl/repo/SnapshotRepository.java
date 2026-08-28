package dev.hmcodes.jrap.crawl.repo;

import dev.hmcodes.jrap.crawl.domain.Snapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SnapshotRepository extends JpaRepository<Snapshot, UUID> {

    @Transactional(readOnly = true)
    Optional<Snapshot> findByAuditIdAndUrl(UUID auditId, String url);

    @Transactional(readOnly = true)
    List<Snapshot> findByAuditIdOrderByFetchedAt(UUID auditId);

    @Transactional(readOnly = true)
    long countByAuditId(UUID auditId);

    @Transactional(readOnly = true)
    long countByAuditIdAndPageType(UUID auditId, String pageType);
}
