package dev.hmcodes.jrap.api.security;

import dev.hmcodes.jrap.tenancy.domain.AppUser;

import java.util.UUID;

/** Authenticated caller identity carried in the security context. */
public record AuthPrincipal(UUID userId, UUID organisationId, String email, AppUser.Role role,
                            boolean platformAdmin) {

    public AuthPrincipal(UUID userId, UUID organisationId, String email, AppUser.Role role) {
        this(userId, organisationId, email, role, false);
    }

}
