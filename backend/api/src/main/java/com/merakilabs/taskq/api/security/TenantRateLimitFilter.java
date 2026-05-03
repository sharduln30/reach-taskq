package com.merakilabs.taskq.api.security;

import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.port.RateLimiter;
import com.merakilabs.taskq.core.port.RateLimiter.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-tenant token-bucket gate in front of every {@code /v1/**} call. Runs after
 * {@link ApiKeyAuthenticationFilter} so we have a Tenant in the security context.
 *
 * <p>The Redis EVALSHA round trip is dispatched via Lettuce's async API and the request
 * thread parks on a tight ({@code redis-timeout-ms}) deadline. Under virtual threads this
 * is essentially free (no carrier-thread pin), so we keep the simple sync-looking control
 * flow without paying a thread-pin cost. On timeout / Redis error we fail open by default
 * (configurable via {@code taskq.ratelimit.fail-open}) — protects user latency at the
 * cost of letting bursts through during Redis blips.
 */
public class TenantRateLimitFilter extends OncePerRequestFilter {

    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";

    private final RateLimiter limiter;
    private final long timeoutMs;
    private final boolean failOpen;

    public TenantRateLimitFilter(
            final RateLimiter limiter,
            @Value("${taskq.ratelimit.redis-timeout-ms:50}") final long timeoutMs,
            @Value("${taskq.ratelimit.fail-open:true}") final boolean failOpen) {
        this.limiter = limiter;
        this.timeoutMs = Math.max(timeoutMs, 1L);
        this.failOpen = failOpen;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        final String path = request.getRequestURI();
        return path == null || !path.startsWith("/v1/");
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain chain)
            throws ServletException, IOException {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof TenantAuthentication ta)) {
            chain.doFilter(request, response);
            return;
        }
        final Tenant tenant = ta.tenant();
        final Decision decision = decide(tenant);

        response.setHeader(HEADER_LIMIT, Integer.toString(tenant.rateLimitBurst()));
        response.setHeader(HEADER_REMAINING, Long.toString(Math.max(decision.remaining(), 0)));

        if (!decision.allowed()) {
            final long retrySec = Math.max(1L, decision.retryAfter().toSeconds());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retrySec));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter()
                    .write("{\"error\":\"rate_limited\",\"message\":\"tenant rate limit exceeded\","
                            + "\"retryAfterMs\":" + decision.retryAfter().toMillis() + "}");
            return;
        }
        chain.doFilter(request, response);
    }

    private Decision decide(final Tenant tenant) {
        try {
            return limiter
                    .tryAcquireAsync(tenant.id(), tenant.rateLimitRps(), tenant.rateLimitBurst())
                    .toCompletableFuture()
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException te) {
            return failOpen ? Decision.allow(0) : Decision.deny(Duration.ofMillis(100));
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return failOpen ? Decision.allow(0) : Decision.deny(Duration.ofMillis(100));
        } catch (final Exception e) {
            return failOpen ? Decision.allow(0) : Decision.deny(Duration.ofMillis(100));
        }
    }
}
