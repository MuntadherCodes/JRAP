package dev.hmcodes.jrap.reporting.repo;

import dev.hmcodes.jrap.reporting.domain.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    @Transactional(readOnly = true)
    List<Report> findByAuditIdOrderByVersionDesc(UUID auditId);

    @Transactional(readOnly = true)
    Optional<Report> findFirstByAuditIdOrderByVersionDesc(UUID auditId);

    @Transactional(readOnly = true)
    Optional<Report> findFirstByAuditIdAndStatusOrderByVersionDesc(UUID auditId, Report.Status status);
}
