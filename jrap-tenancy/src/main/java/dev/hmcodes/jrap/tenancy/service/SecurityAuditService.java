package dev.hmcodes.jrap.tenancy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.tenancy.domain.SecurityAuditLogEntry;
import dev.hmcodes.jrap.tenancy.repo.SecurityAuditLogRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Records security-relevant events (FR-AUTH-5). Uses REQUIRES_NEW so a failing business
 * transaction (e.g. a rejected login) still leaves its audit trail.
 */
@Service
public class SecurityAuditService {

    private final SecurityAuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SecurityAuditService(SecurityAuditLogRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String eventType, UUID organisationId, UUID actorUserId,
                       String actorEmail, Map<String, Object> details, String sourceIp) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        repository.save(new SecurityAuditLogEntry(
                clock.instant(), organisationId, actorUserId, actorEmail, eventType, json, sourceIp));
    }
}
