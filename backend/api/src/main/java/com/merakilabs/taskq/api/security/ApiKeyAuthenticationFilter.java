package com.merakilabs.taskq.api.security;

import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.port.TenantRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-request API-key auth. Resolves the {@link Tenant} from a SHA-256 of the
 * {@code X-API-Key} header.
 *
 * <p>The {@code (hash → Tenant)} lookup is the only DB round trip in front of the
 * controller, so it lives behind a very short TTL in-memory cache to keep a hot
 * tenant from generating a SELECT on every request. Cache hit ≈ 0 µs; miss path is
 * the original SELECT. The cache is eventually-consistent (stale tenant updates take
 * ≤ {@code TTL_NANOS} to propagate) which is acceptable for tenant-state changes that
 * are rare relative to request volume.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private static final long TTL_NANOS = 30L * 1_000_000_000L; // 30s

    private final TenantRepository tenants;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

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
            final Optional<Tenant> tenant = lookup(hash);
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

    private Optional<Tenant> lookup(final String hash) {
        final long now = System.nanoTime();
        final CacheEntry hit = cache.get(hash);
        if (hit != null && (now - hit.loadedAt) < TTL_NANOS) {
            return hit.tenant;
        }
        final Optional<Tenant> fresh = tenants.findByApiKeyHash(hash);
        cache.put(hash, new CacheEntry(fresh, now));
        return fresh;
    }

    /**
     * Force-refresh the cached tenant for the given api key. Called by mutation
     * endpoints (e.g. PATCH /v1/tenants/me) so the caller does not see stale data
     * on the very next request through the same instance.
     */
    public void invalidate(final String hash) {
        cache.remove(hash);
    }

    /**
     * Drop every cached tenant. Used when a mutation endpoint can't easily map the
     * mutated tenant id back to its api-key hash; tenant churn is rare so the next
     * few requests paying a DB round-trip is acceptable.
     */
    public void invalidateAll() {
        cache.clear();
    }

    private record CacheEntry(Optional<Tenant> tenant, long loadedAt) {}
}
