package dev.hmcodes.jrap.registry.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditScheduleRepository extends JpaRepository<AuditSchedule, UUID> {

    @Transactional(readOnly = true)
    Optional<AuditSchedule> findByJournalId(UUID journalId);

    @Transactional(readOnly = true)
    List<AuditSchedule> findByActiveTrueAndNextRunAtBefore(Instant cutoff);

    @Transactional(readOnly = true)
    List<AuditSchedule> findByActiveTrueAndLastAuditIdIsNotNull();
}
