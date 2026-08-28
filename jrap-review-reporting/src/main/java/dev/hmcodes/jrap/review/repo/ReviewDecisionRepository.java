package dev.hmcodes.jrap.review.repo;

import dev.hmcodes.jrap.review.domain.ReviewDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, UUID> {

    @Transactional(readOnly = true)
    List<ReviewDecision> findByAuditIdOrderByCreatedAtDesc(UUID auditId);

    @Transactional(readOnly = true)
    List<ReviewDecision> findByTargetIdOrderByCreatedAtDesc(UUID targetId);
}
