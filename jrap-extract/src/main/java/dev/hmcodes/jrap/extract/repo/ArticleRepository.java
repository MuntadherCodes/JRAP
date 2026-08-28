package dev.hmcodes.jrap.extract.repo;

import dev.hmcodes.jrap.extract.domain.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ArticleRepository extends JpaRepository<Article, UUID> {

    @Transactional(readOnly = true)
    List<Article> findByAuditIdOrderByCreatedAt(UUID auditId);

    @Transactional(readOnly = true)
    long countByAuditId(UUID auditId);

    @Transactional(readOnly = true)
    long countByAuditIdAndNeedsReviewTrue(UUID auditId);

    @Transactional(readOnly = true)
    boolean existsByAuditIdAndSnapshotId(UUID auditId, UUID snapshotId);

    @Transactional(readOnly = true)
    List<Article> findByAuditIdAndDoiIsNotNullOrderByCreatedAt(UUID auditId);
}
