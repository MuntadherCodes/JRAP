package dev.hmcodes.jrap.crawl.repo;

import dev.hmcodes.jrap.crawl.domain.CrawlTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrawlTaskRepository extends JpaRepository<CrawlTask, UUID> {

    @Transactional(readOnly = true)
    Optional<CrawlTask> findFirstByAuditIdAndStatusOrderByCreatedAt(UUID auditId, CrawlTask.Status status);

    @Transactional(readOnly = true)
    boolean existsByAuditIdAndUrl(UUID auditId, String url);

    @Transactional(readOnly = true)
    long countByAuditIdAndStatus(UUID auditId, CrawlTask.Status status);

    @Transactional(readOnly = true)
    List<CrawlTask> findByAuditIdAndStatusIn(UUID auditId, List<CrawlTask.Status> statuses);
}
