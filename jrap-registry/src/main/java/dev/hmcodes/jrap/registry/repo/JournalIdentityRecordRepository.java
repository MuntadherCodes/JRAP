package dev.hmcodes.jrap.registry.repo;

import dev.hmcodes.jrap.registry.domain.JournalIdentityRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface JournalIdentityRecordRepository extends JpaRepository<JournalIdentityRecord, UUID> {

    // @Transactional so RLS sees the tenant GUCs even outside a service transaction.
    @Transactional(readOnly = true)
    List<JournalIdentityRecord> findByJournalIdOrderBySource(UUID journalId);
}
