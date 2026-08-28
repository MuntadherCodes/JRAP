package dev.hmcodes.jrap.tenancy.service;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.domain.Organisation;
import dev.hmcodes.jrap.tenancy.domain.RefreshToken;
import dev.hmcodes.jrap.tenancy.repo.AppUserRepository;
import dev.hmcodes.jrap.tenancy.repo.OrganisationRepository;
import dev.hmcodes.jrap.tenancy.repo.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Login, refresh-token rotation with reuse detection, logout, TOTP management (FR-AUTH-2). */
@Service
public class AuthenticationService {

    private static final Duration REFRESH_TTL = Duration.ofDays(14);

    public record AuthenticatedUser(AppUser user, String refreshToken) {}

    private final AppUserRepository users;
    private final OrganisationRepository organisations;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordHasher passwordHasher;
    private final TokenHasher tokenHasher;
    private final TotpService totpService;
    private final SecurityAuditService audit;
    private final TenantTx tenantTx;
    private final Clock clock;

    public AuthenticationService(AppUserRepository users, OrganisationRepository organisations,
                                 RefreshTokenRepository refreshTokens,
                                 PasswordHasher passwordHasher, TokenHasher tokenHasher,
                                 TotpService totpService, SecurityAuditService audit,
                                 TenantTx tenantTx, Clock clock) {
        this.users = users;
        this.organisations = organisations;
        this.refreshTokens = refreshTokens;
        this.passwordHasher = passwordHasher;
        this.tokenHasher = tokenHasher;
        this.totpService = totpService;
        this.audit = audit;
        this.tenantTx = tenantTx;
        this.clock = clock;
    }

    public AuthenticatedUser login(String email, String password, String totpCode, String sourceIp) {
        return tenantTx.asSystem(() -> {
            String normalisedEmail = RegistrationService.normalise(email);
            AppUser user = users.findByEmail(normalisedEmail).orElse(null);
            boolean passwordOk = user != null && passwordHasher.matches(password, user.getPasswordHash());
            boolean organisationArchived = user != null && organisations.findById(user.getOrganisationId())
                    .map(org -> org.getStatus() == Organisation.Status.ARCHIVED)
                    .orElse(true);
            if (!passwordOk || user.getStatus() != AppUser.Status.ACTIVE || organisationArchived) {
                audit.record("LOGIN_FAILURE", user == null ? null : user.getOrganisationId(),
                        user == null ? null : user.getId(), normalisedEmail,
                        Map.of("reason", !passwordOk ? "bad-credentials"
                                : organisationArchived ? "organisation-archived" : "inactive-account"), sourceIp);
                throw ApiException.unauthorized("bad-credentials", "Email or password is incorrect");
            }
            if (user.isTotpEnabled()) {
                if (totpCode == null || totpCode.isBlank()) {
                    throw ApiException.unauthorized("totp-required", "A TOTP code is required for this account");
                }
                if (!totpService.verify(user.getTotpSecret(), totpCode)) {
                    audit.record("LOGIN_FAILURE", user.getOrganisationId(), user.getId(), normalisedEmail,
                            Map.of("reason", "bad-totp"), sourceIp);
                    throw ApiException.unauthorized("bad-totp", "TOTP code is incorrect");
                }
            }
            Instant now = clock.instant();
            String rawRefresh = tokenHasher.newToken();
            refreshTokens.save(new RefreshToken(UUID.randomUUID(), user.getId(), user.getOrganisationId(),
                    tokenHasher.hash(rawRefresh), now, now.plus(REFRESH_TTL)));
            audit.record("LOGIN_SUCCESS", user.getOrganisationId(), user.getId(), normalisedEmail, Map.of(), sourceIp);
            return new AuthenticatedUser(user, rawRefresh);
        });
    }

    public AuthenticatedUser refresh(String rawRefreshToken, String sourceIp) {
        return tenantTx.asSystem(() -> {
            RefreshToken presented = refreshTokens.findByTokenHashForUpdate(tokenHasher.hash(rawRefreshToken))
                    .orElseThrow(() -> ApiException.unauthorized("invalid-refresh-token", "Refresh token is not valid"));
            Instant now = clock.instant();
            if (presented.getRevokedAt() != null) {
                // Rotation reuse: a previously rotated token was presented again — revoke the whole
                // family. The revocation runs in its OWN transaction so it survives the 401 thrown
                // below (the surrounding transaction rolls back on the exception).
                UUID compromisedUserId = presented.getUserId();
                tenantTx.asSystem(() -> refreshTokens.revokeAllForUser(compromisedUserId, now));
                audit.record("REFRESH_TOKEN_REUSE", presented.getOrganisationId(), presented.getUserId(),
                        null, Map.of("tokenId", presented.getId().toString()), sourceIp);
                throw ApiException.unauthorized("invalid-refresh-token", "Refresh token is not valid");
            }
            if (!presented.isActive(now)) {
                throw ApiException.unauthorized("invalid-refresh-token", "Refresh token has expired");
            }
            AppUser user = users.findById(presented.getUserId())
                    .filter(u -> u.getStatus() == AppUser.Status.ACTIVE)
                    .orElseThrow(() -> ApiException.unauthorized("invalid-refresh-token", "Account is not active"));
            String rawNext = tokenHasher.newToken();
            RefreshToken next = new RefreshToken(UUID.randomUUID(), user.getId(), user.getOrganisationId(),
                    tokenHasher.hash(rawNext), now, now.plus(REFRESH_TTL));
            refreshTokens.save(next);
            presented.revoke(now, next.getId());
            return new AuthenticatedUser(user, rawNext);
        });
    }

    public void logout(String rawRefreshToken) {
        tenantTx.asSystem(() ->
                refreshTokens.findByTokenHash(tokenHasher.hash(rawRefreshToken))
                        .ifPresent(t -> t.revoke(clock.instant(), null)));
    }

    public record TotpSetup(String secret, String otpauthUri) {}

    /** Tenant-scoped: a user configures TOTP on their own account. */
    @Transactional
    public TotpSetup setupTotp(UUID userId) {
        AppUser user = requireUser(userId);
        String secret = totpService.generateSecret();
        user.setTotpSecret(secret);
        user.setTotpEnabled(false);
        return new TotpSetup(secret, totpService.otpauthUri(secret, user.getEmail()));
    }

    @Transactional
    public void enableTotp(UUID userId, String code) {
        AppUser user = requireUser(userId);
        if (user.getTotpSecret() == null || !totpService.verify(user.getTotpSecret(), code)) {
            throw ApiException.badRequest("bad-totp", "TOTP code is incorrect");
        }
        user.setTotpEnabled(true);
        audit.record("TOTP_ENABLED", user.getOrganisationId(), user.getId(), user.getEmail(), Map.of(), null);
    }

    private AppUser requireUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("user-not-found", "User not found"));
    }
}
