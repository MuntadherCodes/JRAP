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
    private final Clock clock;

    public JwtService(@Value("${jrap.security.jwt-secret}") String base64Secret,
                      @Value("${jrap.security.access-token-ttl:PT15M}") Duration accessTtl,
                      Clock clock) {
        this.key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
        this.accessTtl = accessTtl;
        this.clock = clock;
    }

    public String issueAccessToken(AppUser user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("org", user.getOrganisationId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
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
            return Optional.of(new AuthPrincipal(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get("org", String.class)),
                    claims.get("email", String.class),
                    AppUser.Role.valueOf(claims.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // NullPointerException: a validly signed token missing expected claims.
            return Optional.empty();
        }
    }
}
