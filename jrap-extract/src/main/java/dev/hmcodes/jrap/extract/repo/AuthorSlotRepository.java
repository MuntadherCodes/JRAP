package dev.hmcodes.jrap.extract.repo;

import dev.hmcodes.jrap.extract.domain.AuthorSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface AuthorSlotRepository extends JpaRepository<AuthorSlot, UUID> {

    @Transactional(readOnly = true)
    List<AuthorSlot> findByArticleIdInOrderByArticleIdAscPositionAsc(List<UUID> articleIds);

    @Transactional(readOnly = true)
    long countByArticleIdIn(List<UUID> articleIds);
}
