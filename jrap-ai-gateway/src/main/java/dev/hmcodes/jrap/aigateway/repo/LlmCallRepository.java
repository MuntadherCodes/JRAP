package dev.hmcodes.jrap.aigateway.repo;

import dev.hmcodes.jrap.aigateway.domain.LlmCall;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface LlmCallRepository extends JpaRepository<LlmCall, UUID> {

    @Transactional(readOnly = true)
    @Query("""
            select coalesce(sum(coalesce(c.inputTokens, c.requestChars / 4)
                 + coalesce(c.outputTokens, 0)), 0)
            from LlmCall c where c.auditId = :auditId
            """)
    long tokensUsedForAudit(@Param("auditId") UUID auditId);

    @Transactional(readOnly = true)
    long countByAuditId(UUID auditId);
}
