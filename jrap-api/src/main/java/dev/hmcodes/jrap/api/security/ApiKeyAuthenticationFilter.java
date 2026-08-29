package dev.hmcodes.jrap.api.security;

import dev.hmcodes.jrap.common.tenant.TenantContext;
import dev.hmcodes.jrap.tenancy.domain.ApiKey;
import dev.hmcodes.jrap.tenancy.domain.AppUser;
import dev.hmcodes.jrap.tenancy.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * FR-AUTH-4: authenticates public-API requests carrying an organisation API key
 * (X-Api-Key header, or Authorization: Bearer jrap_…). Scopes map onto the existing
 * role model — write-scoped keys act as ANALYST, read-only keys as VIEWER — and the
 * per-key rate limit answers 429 before any work happens.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeys;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String secret = keyFrom(request);
        if (secret == null) {
            filterChain.doFilter(request, response);
            return;
        }
        Optional<ApiKey> resolved = apiKeys.resolve(secret);
        if (resolved.isEmpty()) {
            // Unknown or revoked key: fall through unauthenticated (401 at the entry point).
            filterChain.doFilter(request, response);
            return;
        }
        ApiKey key = resolved.get();
        // Scope refusal happens BEFORE the rate limit so a rejected request does not
        // consume the caller's window.
        boolean mutating = !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                || "OPTIONS".equals(request.getMethod()));
        if (mutating && !ApiKeyService.hasScope(key, "write")) {
            respond(response, 403, "read-only-key",
                    "This API key has the 'read' scope only.");
            return;
        }
        if (!apiKeys.allowRequest(key)) {
            respond(response, 429, "rate-limited",
                    "This API key exceeded its per-minute rate limit (FR-AUTH-4).");
            return;
        }
        try {
            AppUser.Role role = ApiKeyService.hasScope(key, "write")
                    ? AppUser.Role.ANALYST : AppUser.Role.VIEWER;
            AuthPrincipal principal = new AuthPrincipal(key.getCreatedBy(), key.getOrganisationId(),
                    "api-key:" + key.getName(), role, false);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
            TenantContext.setOrganisation(key.getOrganisationId());
            apiKeys.touch(key.getId());
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private static String keyFrom(HttpServletRequest request) {
        String header = request.getHeader("X-Api-Key");
        if (header != null && header.startsWith("jrap_")) {
            return header;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer jrap_")) {
            return auth.substring(7);
        }
        return null;
    }

    private static void respond(HttpServletResponse response, int status, String title,
                                String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }
}
