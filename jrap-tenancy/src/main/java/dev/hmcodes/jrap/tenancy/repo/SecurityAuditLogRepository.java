package dev.hmcodes.jrap.tenancy.repo;

import dev.hmcodes.jrap.tenancy.domain.SecurityAuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLogEntry, Long> {

    List<SecurityAuditLogEntry> findTop100ByOrganisationIdOrderByOccurredAtDesc(UUID organisationId);
}
