package dev.hmcodes.jrap.registry.repo;

import dev.hmcodes.jrap.registry.domain.OrgQuota;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrgQuotaRepository extends JpaRepository<OrgQuota, UUID> {
}
