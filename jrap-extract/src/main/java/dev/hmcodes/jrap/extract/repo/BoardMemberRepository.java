package dev.hmcodes.jrap.extract.repo;

import dev.hmcodes.jrap.extract.domain.BoardMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface BoardMemberRepository extends JpaRepository<BoardMember, UUID> {

    @Transactional(readOnly = true)
    List<BoardMember> findByAuditIdOrderByRoleAscNameAsc(UUID auditId);

    @Transactional(readOnly = true)
    long countByAuditId(UUID auditId);

    @Transactional(readOnly = true)
    long countByAuditIdAndNeedsReviewTrue(UUID auditId);

    @Transactional(readOnly = true)
    boolean existsByAuditIdAndSnapshotId(UUID auditId, UUID snapshotId);
}
