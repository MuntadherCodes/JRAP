package dev.hmcodes.jrap.common.tenant;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Thread-bound tenant scope (SRS FR-AUTH-3).
 *
 * <p>The current organisation id and the system-access flag are propagated into every
 * database transaction by {@code TenantAwareJpaTransactionManager}, where PostgreSQL
 * row-level-security policies enforce isolation server-side. Application code can never
 * widen its own scope except through {@link #runAsSystem(Supplier)}, which is reserved
 * for pre-authentication flows (login, registration, token refresh) and platform
 * administration.</p>
 */
public final class TenantContext {

    private record Scope(UUID organisationId, boolean systemAccess) {}

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void setOrganisation(UUID organisationId) {
        CURRENT.set(new Scope(organisationId, false));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<UUID> organisationId() {
        Scope s = CURRENT.get();
        return s == null ? Optional.empty() : Optional.ofNullable(s.organisationId());
    }

    public static UUID requireOrganisationId() {
        return organisationId().orElseThrow(() ->
                new IllegalStateException("No tenant in context for a tenant-scoped operation"));
    }

    public static boolean hasSystemAccess() {
        Scope s = CURRENT.get();
        return s != null && s.systemAccess();
    }

    /** Runs {@code action} with system-level data access, restoring the previous scope afterwards. */
    public static <T> T runAsSystem(Supplier<T> action) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(previous == null ? null : previous.organisationId(), true));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAsSystem(Runnable action) {
        runAsSystem(() -> {
            action.run();
            return null;
        });
    }
}
