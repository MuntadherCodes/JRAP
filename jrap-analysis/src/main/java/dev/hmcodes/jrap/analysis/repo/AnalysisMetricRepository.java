package dev.hmcodes.jrap.analysis.repo;

import dev.hmcodes.jrap.analysis.domain.AnalysisMetric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface AnalysisMetricRepository extends JpaRepository<AnalysisMetric, UUID> {

    @Transactional(readOnly = true)
    List<AnalysisMetric> findByAuditIdOrderByName(UUID auditId);
}
