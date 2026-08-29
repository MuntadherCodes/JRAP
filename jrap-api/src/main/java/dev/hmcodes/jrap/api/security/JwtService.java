package dev.hmcodes.jrap.api.security;

import dev.hmcodes.jrap.tenancy.domain.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/** Short-lived HMAC-signed JWT access tokens (FR-AUTH-2). Refresh tokens are opaque and DB-backed. */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration accessTtl;
    private final java.util.Set<String> adminEmails;
    private final Clock clock;

    public JwtService(@Value("${jrap.security.jwt-secret}") String base64Secret,
                      @Value("${jrap.security.access-token-ttl:PT15M}") Duration accessTtl,
                      @Value("${jrap.admin.emails:}") java.util.List<String> adminEmails,
                      Clock clock) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
        this.accessTtl = accessTtl;
        this.adminEmails = adminEmails == null ? java.util.Set.of()
                : adminEmails.stream().map(String::trim).map(String::toLowerCase)
                        .filter(s -> !s.isBlank())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.clock = clock;
    }

    /** FR-ADM-1: platform administrators are configured by email (JRAP_ADMIN_EMAILS). */
    public boolean isPlatformAdmin(String email) {
        return email != null && adminEmails.contains(email.toLowerCase(java.util.Locale.ROOT));
    }

    public String issueAccessToken(AppUser user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("org", user.getOrganisationId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("admin", isPlatformAdmin(user.getEmail()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    public Optional<AuthPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Boolean admin = claims.get("admin", Boolean.class);
            return Optional.of(new AuthPrincipal(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get("org", String.class)),
                    claims.get("email", String.class),
                    AppUser.Role.valueOf(claims.get("role", String.class)),
                    Boolean.TRUE.equals(admin)));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // NullPointerException: a validly signed token missing expected claims.
            return Optional.empty();
        }
    }
}
