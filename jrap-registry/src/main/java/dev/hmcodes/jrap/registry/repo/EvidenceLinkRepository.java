package dev.hmcodes.jrap.registry.repo;

import dev.hmcodes.jrap.registry.domain.EvidenceLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceLinkRepository extends JpaRepository<EvidenceLink, EvidenceLink.Key> {

    List<EvidenceLink> findByIdFindingIdIn(List<UUID> findingIds);
}
