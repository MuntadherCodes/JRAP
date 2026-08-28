package dev.hmcodes.jrap.tenancy.service;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.repo.AppUserRepository;
import dev.hmcodes.jrap.tenancy.repo.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tenant-scoped user administration (FR-AUTH-1). All queries run under the tenant row-level filter. */
@Service
public class UserAdminService {

    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final SecurityAuditService audit;
    private final Clock clock;

    public UserAdminService(AppUserRepository users, RefreshTokenRepository refreshTokens,
                            SecurityAuditService audit, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return users.findByOrganisationIdOrderByCreatedAt(TenantContext.requireOrganisationId());
    }

    @Transactional(readOnly = true)
    public AppUser getUser(UUID userId) {
        return requireInTenant(userId);
    }

    @Transactional
    public void changeRole(UUID userId, AppUser.Role newRole, UUID actorUserId, String actorEmail) {
        AppUser user = requireInTenant(userId);
        if (user.getRole() == AppUser.Role.OWNER && newRole != AppUser.Role.OWNER && isLastActiveOwner(user)) {
            throw ApiException.conflict("last-owner", "An organisation must keep at least one active owner");
        }
        AppUser.Role oldRole = user.getRole();
        user.setRole(newRole);
        audit.record("ROLE_CHANGED", user.getOrganisationId(), actorUserId, actorEmail,
                Map.of("userId", userId.toString(), "from", oldRole.name(), "to", newRole.name()), null);
    }

    @Transactional
    public void disableUser(UUID userId, UUID actorUserId, String actorEmail) {
        AppUser user = requireInTenant(userId);
        if (user.getRole() == AppUser.Role.OWNER && isLastActiveOwner(user)) {
            throw ApiException.conflict("last-owner", "An organisation must keep at least one active owner");
        }
        user.setStatus(AppUser.Status.DISABLED);
        refreshTokens.revokeAllForUser(userId, clock.instant());
        audit.record("USER_DISABLED", user.getOrganisationId(), actorUserId, actorEmail,
                Map.of("userId", userId.toString()), null);
    }

    private AppUser requireInTenant(UUID userId) {
        // Row-level security scopes findById to the current tenant: another organisation's
        // user id simply does not exist from this transaction's point of view.
        return users.findById(userId)
                .filter(u -> u.getOrganisationId().equals(TenantContext.requireOrganisationId()))
                .orElseThrow(() -> ApiException.notFound("user-not-found", "User not found"));
    }

    private boolean isLastActiveOwner(AppUser owner) {
        return users.findByOrganisationIdOrderByCreatedAt(owner.getOrganisationId()).stream()
                .filter(u -> u.getRole() == AppUser.Role.OWNER && u.getStatus() == AppUser.Status.ACTIVE)
                .allMatch(u -> u.getId().equals(owner.getId()));
    }
}
