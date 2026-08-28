package dev.hmcodes.jrap.tenancy.repo;

import dev.hmcodes.jrap.tenancy.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    List<AppUser> findByOrganisationIdOrderByCreatedAt(UUID organisationId);
}
