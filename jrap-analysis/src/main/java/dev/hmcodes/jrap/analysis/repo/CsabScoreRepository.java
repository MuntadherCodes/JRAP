package dev.hmcodes.jrap.analysis.repo;

import dev.hmcodes.jrap.analysis.domain.CsabScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface CsabScoreRepository extends JpaRepository<CsabScore, UUID> {

    @Transactional(readOnly = true)
    List<CsabScore> findByAuditIdOrderByCategory(UUID auditId);
}
