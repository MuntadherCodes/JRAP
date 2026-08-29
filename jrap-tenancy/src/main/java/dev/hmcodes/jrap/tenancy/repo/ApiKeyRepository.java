package dev.hmcodes.jrap.tenancy.repo;

import dev.hmcodes.jrap.tenancy.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    @Transactional(readOnly = true)
    List<ApiKey> findByOrganisationIdOrderByCreatedAtDesc(UUID organisationId);

    // Key resolution happens pre-tenant (the key IS the tenant credential), so the
    // lookup runs under system scope in the service.
    @Transactional(readOnly = true)
    Optional<ApiKey> findByKeyHash(String keyHash);
}
