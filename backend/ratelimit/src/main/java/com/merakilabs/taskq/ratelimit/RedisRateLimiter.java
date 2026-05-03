package com.merakilabs.taskq.ratelimit;

import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.RateLimiter;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-tenant token-bucket rate limiter backed by Redis Lua. The hot path is
 * a single round trip ({@code EVALSHA}) — typically &lt;1ms.
 *
 * <p>Failure mode: any Redis error (timeout, connection loss) is logged and
 * either fails open (allow, default — protects user latency) or fails closed
 * (deny, when {@code taskq.ratelimit.fail-open=false} — protects the backend).
 */
public final class RedisRateLimiter implements RateLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String KEY_PREFIX = "taskq:rl:";

    private final StatefulRedisConnection<String, String> conn;
    private final LuaScripts scripts;
    private final MeterRegistry meters;
    private final boolean failOpen;

    @SuppressWarnings("unused") // reserved for async path / future tuning
    private final Duration timeout;

    private final ConcurrentHashMap<String, Counter> allowCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> denyCounters = new ConcurrentHashMap<>();
    private final Counter errorCounter;

    public RedisRateLimiter(
            final StatefulRedisConnection<String, String> conn,
            final LuaScripts scripts,
            final MeterRegistry meters,
            final boolean failOpen,
            final Duration timeout) {
        this.conn = conn;
        this.scripts = scripts;
        this.meters = meters;
        this.failOpen = failOpen;
        this.timeout = timeout;
        this.errorCounter = Counter.builder("taskq.ratelimit.errors")
                .description("Redis errors during rate-limit evaluation")
                .register(meters);
    }

    @Override
    public Decision tryAcquire(final TenantId tenantId, final int rps, final int burst) {
        final String key = KEY_PREFIX + tenantId.value();
        final long now = System.currentTimeMillis();

        try {
            final RedisCommands<String, String> cmds = conn.sync();
            @SuppressWarnings("unchecked")
            final List<Long> result = scripts.eval(
                    cmds,
                    LuaScripts.TOKEN_BUCKET,
                    ScriptOutputType.MULTI,
                    new String[] {key},
                    Integer.toString(rps),
                    Integer.toString(burst),
                    Long.toString(now),
                    "1");
            final boolean allowed = result.get(0) == 1L;
            final long remaining = result.get(1);
            final long retryMs = result.get(2);
            recordDecision(tenantId, allowed);
            return allowed
                    ? Decision.allow(remaining)
                    : Decision.deny(Duration.ofMillis(Math.max(retryMs, 1)));
        } catch (final RedisException e) {
            errorCounter.increment();
            LOG.warn("Redis rate-limit failed for tenant={} failOpen={} err={}", tenantId, failOpen, e.toString());
            if (failOpen) {
                return Decision.allow(burst);
            }
            return Decision.deny(Duration.ofMillis(100));
        }
    }

    /**
     * Non-blocking variant. Lettuce {@code async()} dispatches the EVALSHA on the I/O loop and
     * completes the {@code RedisFuture} on the connection's event-executor; we adapt that to a
     * {@link CompletionStage} so the HTTP filter can attach a continuation instead of pinning the
     * request thread for the duration of the round trip.
     */
    @Override
    public CompletionStage<Decision> tryAcquireAsync(
            final TenantId tenantId, final int rps, final int burst) {
        final String key = KEY_PREFIX + tenantId.value();
        final long now = System.currentTimeMillis();
        final RedisAsyncCommands<String, String> async = conn.async();
        try {
            return scripts
                    .evalAsync(
                            async,
                            LuaScripts.TOKEN_BUCKET,
                            ScriptOutputType.MULTI,
                            new String[] {key},
                            Integer.toString(rps),
                            Integer.toString(burst),
                            Long.toString(now),
                            "1")
                    .<Decision>thenApply(raw -> {
                        @SuppressWarnings("unchecked")
                        final List<Long> result = (List<Long>) raw;
                        final boolean allowed = result.get(0) == 1L;
                        final long remaining = result.get(1);
                        final long retryMs = result.get(2);
                        recordDecision(tenantId, allowed);
                        return allowed
                                ? Decision.allow(remaining)
                                : Decision.deny(Duration.ofMillis(Math.max(retryMs, 1)));
                    })
                    .exceptionally(t -> failOpenOrClosed(tenantId, t));
        } catch (final RuntimeException re) {
            return CompletableFuture.completedFuture(failOpenOrClosed(tenantId, re));
        }
    }

    private Decision failOpenOrClosed(final TenantId tenantId, final Throwable t) {
        errorCounter.increment();
        LOG.warn("Redis rate-limit (async) failed for tenant={} failOpen={} err={}",
                tenantId, failOpen, t == null ? "null" : t.toString());
        if (failOpen) {
            return Decision.allow(0);
        }
        return Decision.deny(Duration.ofMillis(100));
    }

    private void recordDecision(final TenantId tenantId, final boolean allowed) {
        final String t = tenantId.value().toString();
        if (allowed) {
            allowCounters
                    .computeIfAbsent(t, this::newAllow)
                    .increment();
        } else {
            denyCounters
                    .computeIfAbsent(t, this::newDeny)
                    .increment();
        }
    }

    private Counter newAllow(final String tenant) {
        return Counter.builder("taskq.ratelimit.decisions")
                .tags("tenant", tenant, "outcome", "allow")
                .description("Rate-limit decisions per tenant")
                .register(meters);
    }

    private Counter newDeny(final String tenant) {
        return Counter.builder("taskq.ratelimit.decisions")
                .tags("tenant", tenant, "outcome", "deny")
                .description("Rate-limit decisions per tenant")
                .register(meters);
    }
}
