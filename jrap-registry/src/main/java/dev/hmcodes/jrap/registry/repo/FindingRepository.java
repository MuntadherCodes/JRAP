package dev.hmcodes.jrap.registry.repo;

import dev.hmcodes.jrap.registry.domain.Finding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<Finding, UUID> {

    List<Finding> findByJournalId(UUID journalId);
}
