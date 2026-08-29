package dev.hmcodes.jrap.registry.platform;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    @Transactional(readOnly = true)
    List<WebhookDelivery> findTop50ByWebhookIdOrderByAttemptedAtDesc(UUID webhookId);
}
