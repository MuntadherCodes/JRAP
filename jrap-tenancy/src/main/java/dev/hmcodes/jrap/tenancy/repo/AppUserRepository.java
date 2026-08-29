package dev.hmcodes.jrap.tenancy.repo;

import dev.hmcodes.jrap.tenancy.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    // Standing rule: derived queries used outside a service transaction carry
    // @Transactional so the RLS GUCs are set (Phase-2 lesson).
    @Transactional(readOnly = true)
    Optional<AppUser> findByEmail(String email);

    @Transactional(readOnly = true)
    List<AppUser> findByOrganisationIdOrderByCreatedAt(UUID organisationId);
}
