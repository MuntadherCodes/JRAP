package dev.hmcodes.jrap.api.security;

import dev.hmcodes.jrap.common.tenant.TenantContext;
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

/**
 * Resolves the bearer token into an {@link AuthPrincipal} and binds the request's tenant
 * scope (FR-AUTH-3). The scope is cleared in a finally block so pooled threads never leak
 * a tenant into the next request.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                jwtService.parse(header.substring(7)).ifPresent(principal -> {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    TenantContext.setOrganisation(principal.organisationId());
                });
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
