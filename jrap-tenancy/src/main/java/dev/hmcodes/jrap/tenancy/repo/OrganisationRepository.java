package dev.hmcodes.jrap.tenancy.repo;

import dev.hmcodes.jrap.tenancy.domain.Organisation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrganisationRepository extends JpaRepository<Organisation, UUID> {
}
