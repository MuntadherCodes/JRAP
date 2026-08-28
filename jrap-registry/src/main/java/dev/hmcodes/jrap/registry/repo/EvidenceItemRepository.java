package dev.hmcodes.jrap.registry.repo;

import dev.hmcodes.jrap.registry.domain.EvidenceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, UUID> {

    List<EvidenceItem> findByJournalId(UUID journalId);
}
