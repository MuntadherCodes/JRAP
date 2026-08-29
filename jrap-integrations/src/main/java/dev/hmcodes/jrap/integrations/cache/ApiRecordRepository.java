package dev.hmcodes.jrap.integrations.cache;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApiRecordRepository extends JpaRepository<ApiRecord, UUID> {

    @Query("""
            select r from ApiRecord r
            where r.source = :source and r.requestKey = :requestKey
              and r.expiresAt > :now and r.statusCode in (200, 404)
            order by r.retrievedAt desc
            """)
    List<ApiRecord> findFresh(@Param("source") String source,
                              @Param("requestKey") String requestKey,
                              @Param("now") Instant now);

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    java.util.Optional<ApiRecord> findFirstBySourceOrderByRetrievedAtDesc(String source);
}
