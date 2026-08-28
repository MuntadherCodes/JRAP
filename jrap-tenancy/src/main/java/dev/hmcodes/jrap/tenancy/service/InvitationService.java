package dev.hmcodes.jrap.tenancy.service;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.domain.VerificationToken;
import dev.hmcodes.jrap.tenancy.repo.AppUserRepository;
import dev.hmcodes.jrap.tenancy.repo.VerificationTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Invitation of users into an organisation with a role (FR-AUTH-1). */
@Service
public class InvitationService {

    private static final Duration INVITE_TTL = Duration.ofDays(7);

    private final AppUserRepository users;
    private final VerificationTokenRepository tokens;
    private final PasswordHasher passwordHasher;
    private final TokenHasher tokenHasher;
    private final EmailSender emailSender;
    private final SecurityAuditService audit;
    private final TenantTx tenantTx;
    private final Clock clock;
    private final String baseUrl;

    public InvitationService(AppUserRepository users, VerificationTokenRepository tokens,
                             PasswordHasher passwordHasher, TokenHasher tokenHasher, EmailSender emailSender,
                             SecurityAuditService audit, TenantTx tenantTx, Clock clock,
                             @Value("${jrap.app.base-url}") String baseUrl) {
        this.users = users;
        this.tokens = tokens;
        this.passwordHasher = passwordHasher;
        this.tokenHasher = tokenHasher;
        this.emailSender = emailSender;
        this.audit = audit;
        this.tenantTx = tenantTx;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    /**
     * Invites a user into the caller's organisation. Caller authorisation (owner role) is
     * enforced at the API layer; the tenant row-level filter scopes every statement here.
     * A duplicate email anywhere on the platform surfaces as the unique-index violation
     * caught below — the tenant-scoped transaction cannot (and must not) read other
     * organisations' users to pre-check.
     */
    @Transactional
    public UUID invite(String email, AppUser.Role role, UUID invitedByUserId, String invitedByEmail) {
        UUID orgId = TenantContext.requireOrganisationId();
        String normalisedEmail = RegistrationService.normalise(email);
        Instant now = clock.instant();
        AppUser invited = new AppUser(UUID.randomUUID(), orgId, normalisedEmail,
                normalisedEmail.substring(0, normalisedEmail.indexOf('@')),
                role, AppUser.Status.INVITED, now);
        try {
            users.saveAndFlush(invited);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("email-in-use", "This email address is already registered");
        }

        String rawToken = tokenHasher.newToken();
        tokens.save(new VerificationToken(UUID.randomUUID(), invited.getId(), orgId,
                tokenHasher.hash(rawToken), VerificationToken.Purpose.INVITE, now.plus(INVITE_TTL), now));
        // TODO(prod email adapter): send after commit (TransactionSynchronization.afterCommit / outbox).
        emailSender.send(normalisedEmail, "You have been invited to JRAP",
                "Accept your invitation: " + baseUrl + "/accept-invitation?token=" + rawToken);

        audit.record("USER_INVITED", orgId, invitedByUserId, invitedByEmail,
                Map.of("invitedEmail", normalisedEmail, "role", role.name()), null);
        return invited.getId();
    }

    public void acceptInvitation(String rawToken, String password, String displayName) {
        tenantTx.asSystem(() -> {
            VerificationToken token = tokens
                    .findByTokenHashAndPurpose(tokenHasher.hash(rawToken), VerificationToken.Purpose.INVITE)
                    .orElseThrow(() -> ApiException.badRequest("invalid-token", "Invitation token is invalid"));
            Instant now = clock.instant();
            if (token.getUsedAt() != null || token.getExpiresAt().isBefore(now)) {
                throw ApiException.badRequest("invalid-token", "Invitation has expired or was already used");
            }
            token.markUsed(now);
            AppUser user = users.findById(token.getUserId())
                    .orElseThrow(() -> ApiException.badRequest("invalid-token", "Invitation token is invalid"));
            user.setPasswordHash(passwordHasher.hash(password));
            user.setDisplayName(displayName.trim());
            user.setStatus(AppUser.Status.ACTIVE);
            user.setEmailVerifiedAt(now);
            audit.record("INVITATION_ACCEPTED", user.getOrganisationId(), user.getId(), user.getEmail(), Map.of(), null);
        });
    }
}
