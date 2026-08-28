package dev.hmcodes.jrap.analysis.repo;

import dev.hmcodes.jrap.analysis.domain.GatewayCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface GatewayCheckRepository extends JpaRepository<GatewayCheck, UUID> {

    @Transactional(readOnly = true)
    List<GatewayCheck> findByAuditIdOrderByCode(UUID auditId);

    @Transactional(readOnly = true)
    boolean existsByAuditId(UUID auditId);
}
