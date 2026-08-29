package dev.hmcodes.jrap.registry.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ActionItemRepository extends JpaRepository<ActionItem, UUID> {

    @Transactional(readOnly = true)
    List<ActionItem> findByJournalIdOrderByCreatedAt(UUID journalId);

    @Transactional(readOnly = true)
    long countByJournalIdAndStatusNot(UUID journalId, ActionItem.Status status);

    @Transactional(readOnly = true)
    boolean existsByJournalIdAndCatalogueActionId(UUID journalId, String catalogueActionId);
}
