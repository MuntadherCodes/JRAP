package dev.hmcodes.jrap.tenancy.service;

import dev.hmcodes.jrap.common.error.ApiException;
import dev.hmcodes.jrap.tenancy.domain.ApiKey;
import dev.hmcodes.jrap.tenancy.repo.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FR-AUTH-4: per-organisation API keys with scoped permissions and per-key rate limits.
 * The secret ("jrap_" + 40 hex chars) is returned exactly once; only its SHA-256 lands
 * in the database. Key lifecycle events go to the immutable security audit log
 * (FR-AUTH-5). The rate limiter is per-instance in-memory (single-node beta;
 * a shared store is a Phase-9 NFR-SCAL item).
 */
@Service
public class ApiKeyService {

    public record CreatedKey(ApiKey key, String secret) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository keys;
    private final TenantTx tenantTx;
    private final SecurityAuditService securityAudit;
    private final Clock clock;

    /** key id -> (epoch minute, counter) */
    private final ConcurrentHashMap<UUID, long[]> windows = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyRepository keys, TenantTx tenantTx,
                         SecurityAuditService securityAudit, Clock clock) {
        this.keys = keys;
        this.tenantTx = tenantTx;
        this.securityAudit = securityAudit;
        this.clock = clock;
    }

    @Transactional
    public CreatedKey create(UUID organisationId, String name, List<String> scopes,
                             int rateLimitPerMinute, UUID actorUserId, String actorEmail) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name-required", "API keys need a name.");
        }
        List<String> cleanScopes = (scopes == null || scopes.isEmpty()) ? List.of("read")
                : scopes.stream().map(String::trim).filter(s -> s.equals("read") || s.equals("write"))
                        .distinct().toList();
        if (cleanScopes.isEmpty()) {
            throw ApiException.badRequest("bad-scopes", "Valid scopes are 'read' and 'write'.");
        }
        int limit = rateLimitPerMinute <= 0 ? 60 : Math.min(rateLimitPerMinute, 6000);

        byte[] random = new byte[20];
        RANDOM.nextBytes(random);
        String secret = "jrap_" + HexFormat.of().formatHex(random);
        ApiKey key = new ApiKey(UUID.randomUUID(), organisationId, name.trim(),
                secret.substring(0, 12) + "…", sha256(secret),
                "[" + cleanScopes.stream().map(s -> "\"" + s + "\"")
                        .reduce((a, b) -> a + "," + b).orElse("") + "]",
                limit, actorUserId, clock.instant());
        keys.save(key);
        securityAudit.record("api-key.created", organisationId, actorUserId, actorEmail,
                Map.of("keyId", key.getId().toString(), "name", key.getName(),
                        "scopes", cleanScopes.toString()), null);
        return new CreatedKey(key, secret);
    }

    @Transactional
    public void revoke(UUID keyId, UUID organisationId, UUID actorUserId, String actorEmail) {
        ApiKey key = keys.findById(keyId)
                .filter(k -> k.getOrganisationId().equals(organisationId))
                .orElseThrow(() -> ApiException.notFound("key-not-found", "API key not found"));
        if (!key.isRevoked()) {
            key.revoke(clock.instant());
            securityAudit.record("api-key.revoked", organisationId, actorUserId, actorEmail,
                    Map.of("keyId", key.getId().toString(), "name", key.getName()), null);
        }
    }

    /** Pre-auth resolution: the key itself establishes the tenant, so this runs as system. */
    public Optional<ApiKey> resolve(String secret) {
        if (secret == null || !secret.startsWith("jrap_")) {
            return Optional.empty();
        }
        String hash = sha256(secret);
        return tenantTx.asSystem(() -> keys.findByKeyHash(hash))
                .filter(key -> !key.isRevoked());
    }

    /** Records a use (last_used_at) without holding up the request path on failure. */
    public void touch(UUID keyId) {
        try {
            tenantTx.asSystem(() -> {
                keys.findById(keyId).ifPresent(k -> k.touch(clock.instant()));
                return null;
            });
        } catch (Exception ignored) {
            // best-effort bookkeeping
        }
    }

    /** FR-AUTH-4 per-key rate limit: fixed one-minute window, per instance. */
    public boolean allowRequest(ApiKey key) {
        long minute = clock.instant().getEpochSecond() / 60;
        long[] window = windows.compute(key.getId(), (id, current) -> {
            if (current == null || current[0] != minute) {
                return new long[]{minute, 1};
            }
            current[1]++;
            return current;
        });
        return window[1] <= key.getRateLimitPerMinute();
    }

    public static boolean hasScope(ApiKey key, String scope) {
        return key.getScopes() != null && key.getScopes().contains("\"" + scope + "\"");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
