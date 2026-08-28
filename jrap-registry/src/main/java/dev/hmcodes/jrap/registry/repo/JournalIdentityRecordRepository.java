package dev.hmcodes.jrap.registry.repo;

import dev.hmcodes.jrap.registry.domain.JournalIdentityRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JournalIdentityRecordRepository extends JpaRepository<JournalIdentityRecord, UUID> {

    List<JournalIdentityRecord> findByJournalIdOrderBySource(UUID journalId);
}
