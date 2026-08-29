package dev.hmcodes.jrap.registry.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface WebhookRepository extends JpaRepository<Webhook, UUID> {

    @Transactional(readOnly = true)
    List<Webhook> findByOrganisationIdOrderByCreatedAt(UUID organisationId);

    @Transactional(readOnly = true)
    List<Webhook> findByActiveTrue();
}
