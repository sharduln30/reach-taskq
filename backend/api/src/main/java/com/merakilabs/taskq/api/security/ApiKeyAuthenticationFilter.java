package com.merakilabs.taskq.api.security;

import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.port.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final TenantRepository tenants;

    public ApiKeyAuthenticationFilter(final TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain chain)
            throws ServletException, IOException {
        final String apiKey = request.getHeader(HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            final String hash = ApiKeyHasher.hash(apiKey);
            final Optional<Tenant> tenant = tenants.findByApiKeyHash(hash);
            if (tenant.isPresent() && tenant.get().active()) {
                final TenantAuthentication auth = new TenantAuthentication(tenant.get());
                SecurityContextHolder.getContext().setAuthentication(auth);
                MDC.put("tenant_id", tenant.get().id().toString());
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("tenant_id");
        }
    }
}
