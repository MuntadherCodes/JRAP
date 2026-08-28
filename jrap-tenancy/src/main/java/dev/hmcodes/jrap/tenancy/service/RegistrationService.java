package dev.hmcodes.jrap.tenancy.service;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.domain.Organisation;
import dev.hmcodes.jrap.tenancy.domain.VerificationToken;
import dev.hmcodes.jrap.tenancy.repo.AppUserRepository;
import dev.hmcodes.jrap.tenancy.repo.OrganisationRepository;
import dev.hmcodes.jrap.tenancy.repo.VerificationTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Organisation self-registration with email verification (FR-AUTH-1). */
@Service
public class RegistrationService {

    private static final Duration VERIFY_TTL = Duration.ofHours(24);

    private final OrganisationRepository organisations;
    private final AppUserRepository users;
    private final VerificationTokenRepository tokens;
    private final PasswordHasher passwordHasher;
    private final TokenHasher tokenHasher;
    private final EmailSender emailSender;
    private final SecurityAuditService audit;
    private final TenantTx tenantTx;
    private final Clock clock;
    private final String baseUrl;

    public RegistrationService(OrganisationRepository organisations, AppUserRepository users,
                               VerificationTokenRepository tokens, PasswordHasher passwordHasher,
                               TokenHasher tokenHasher, EmailSender emailSender,
                               SecurityAuditService audit, TenantTx tenantTx, Clock clock,
                               @Value("${jrap.app.base-url}") String baseUrl) {
        this.organisations = organisations;
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

    public UUID registerOrganisation(String organisationName, String email, String password, String displayName) {
        return tenantTx.asSystem(() -> {
            String normalisedEmail = normalise(email);
            if (users.findByEmail(normalisedEmail).isPresent()) {
                throw ApiException.conflict("email-in-use", "This email address is already registered");
            }
            Instant now = clock.instant();
            Organisation org = new Organisation(UUID.randomUUID(), organisationName.trim(),
                    Organisation.Status.PENDING_VERIFICATION, now);
            organisations.save(org);

            AppUser owner = new AppUser(UUID.randomUUID(), org.getId(), normalisedEmail, displayName.trim(),
                    AppUser.Role.OWNER, AppUser.Status.PENDING_VERIFICATION, now);
            owner.setPasswordHash(passwordHasher.hash(password));
            try {
                users.saveAndFlush(owner);
            } catch (DataIntegrityViolationException e) {
                throw ApiException.conflict("email-in-use", "This email address is already registered");
            }

            String rawToken = tokenHasher.newToken();
            tokens.save(new VerificationToken(UUID.randomUUID(), owner.getId(), org.getId(),
                    tokenHasher.hash(rawToken), VerificationToken.Purpose.VERIFY_EMAIL,
                    now.plus(VERIFY_TTL), now));
            // TODO(prod email adapter): send after commit (TransactionSynchronization.afterCommit / outbox).
        emailSender.send(normalisedEmail, "Verify your JRAP account",
                    "Welcome to JRAP. Verify your email: " + baseUrl + "/verify-email?token=" + rawToken);

            audit.record("ORG_REGISTERED", org.getId(), owner.getId(), normalisedEmail,
                    Map.of("organisationName", org.getName()), null);
            return org.getId();
        });
    }

    public void verifyEmail(String rawToken) {
        tenantTx.asSystem(() -> {
            VerificationToken token = tokens
                    .findByTokenHashAndPurpose(tokenHasher.hash(rawToken), VerificationToken.Purpose.VERIFY_EMAIL)
                    .orElseThrow(() -> ApiException.badRequest("invalid-token", "Verification token is invalid"));
            Instant now = clock.instant();
            if (token.getUsedAt() != null || token.getExpiresAt().isBefore(now)) {
                throw ApiException.badRequest("invalid-token", "Verification token has expired or was already used");
            }
            token.markUsed(now);
            AppUser user = users.findById(token.getUserId())
                    .orElseThrow(() -> ApiException.badRequest("invalid-token", "Verification token is invalid"));
            user.setStatus(AppUser.Status.ACTIVE);
            user.setEmailVerifiedAt(now);
            organisations.findById(user.getOrganisationId()).ifPresent(org -> {
                if (org.getStatus() == Organisation.Status.PENDING_VERIFICATION) {
                    org.setStatus(Organisation.Status.ACTIVE);
                }
            });
            audit.record("EMAIL_VERIFIED", user.getOrganisationId(), user.getId(), user.getEmail(), Map.of(), null);
        });
    }

    static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
