package com.merakilabs.taskq.ratelimit;

import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.ConcurrencyLimiter;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.Range;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-tenant fleet-wide concurrency limiter implemented as a Redis sorted-set
 * (member = lease token, score = wall-clock acquire-ms). Stale holders are
 * pruned by every {@code tryAcquire}, so a worker that dies mid-job releases
 * its slot at most {@code lease_ttl_ms} later.
 */
public final class RedisConcurrencyLimiter implements ConcurrencyLimiter {

    private static final Logger LOG = LoggerFactory.getLogger(RedisConcurrencyLimiter.class);
    private static final String KEY_PREFIX = "taskq:cc:";

    private final StatefulRedisConnection<String, String> conn;
    private final LuaScripts scripts;
    private final MeterRegistry meters;
    private final Duration leaseTtl;
    private final boolean failOpen;

    private final ConcurrentHashMap<String, Counter> rejectCounters = new ConcurrentHashMap<>();
    private final Counter errorCounter;

    public RedisConcurrencyLimiter(
            final StatefulRedisConnection<String, String> conn,
            final LuaScripts scripts,
            final MeterRegistry meters,
            final Duration leaseTtl,
            final boolean failOpen) {
        this.conn = conn;
        this.scripts = scripts;
        this.meters = meters;
        this.leaseTtl = leaseTtl;
        this.failOpen = failOpen;
        this.errorCounter = Counter.builder("taskq.concurrency.errors")
                .description("Redis errors during concurrency-limit evaluation")
                .register(meters);
    }

    @Override
    public boolean tryAcquire(final TenantId tenantId, final int maxConcurrency, final String holderToken) {
        final String key = key(tenantId);
        final long now = System.currentTimeMillis();
        try {
            final RedisCommands<String, String> cmds = conn.sync();
            final Long result = scripts.eval(
                    cmds,
                    LuaScripts.FAIR_SEMAPHORE,
                    ScriptOutputType.INTEGER,
                    new String[] {key},
                    Integer.toString(maxConcurrency),
                    Long.toString(now),
                    Long.toString(leaseTtl.toMillis()),
                    holderToken);
            final boolean ok = result != null && result == 1L;
            if (!ok) {
                rejectCounters
                        .computeIfAbsent(tenantId.value().toString(), this::newReject)
                        .increment();
            }
            return ok;
        } catch (final RedisException e) {
            errorCounter.increment();
            LOG.warn(
                    "Redis concurrency tryAcquire failed tenant={} failOpen={} err={}",
                    tenantId,
                    failOpen,
                    e.toString());
            return failOpen;
        }
    }

    @Override
    public void release(final TenantId tenantId, final String holderToken) {
        try {
            conn.sync().zrem(key(tenantId), holderToken);
        } catch (final RedisException e) {
            errorCounter.increment();
            LOG.warn("Redis concurrency release failed tenant={} err={}", tenantId, e.toString());
        }
    }

    @Override
    public boolean refresh(final TenantId tenantId, final String holderToken) {
        try {
            final RedisCommands<String, String> cmds = conn.sync();
            final Long result = scripts.eval(
                    cmds,
                    LuaScripts.SEMAPHORE_REFRESH,
                    ScriptOutputType.INTEGER,
                    new String[] {key(tenantId)},
                    Long.toString(System.currentTimeMillis()),
                    Long.toString(leaseTtl.toMillis()),
                    holderToken);
            return result != null && result == 1L;
        } catch (final RedisException e) {
            errorCounter.increment();
            LOG.warn("Redis concurrency refresh failed tenant={} err={}", tenantId, e.toString());
            return failOpen;
        }
    }

    @Override
    public long currentUsage(final TenantId tenantId) {
        try {
            final String key = key(tenantId);
            final long now = System.currentTimeMillis();
            final RedisCommands<String, String> cmds = conn.sync();
            cmds.zremrangebyscore(key, Range.create(0d, (double) (now - leaseTtl.toMillis())));
            final Long card = cmds.zcard(key);
            return card == null ? 0L : card;
        } catch (final RedisException e) {
            errorCounter.increment();
            return -1L;
        }
    }

    private static String key(final TenantId tenantId) {
        return KEY_PREFIX + tenantId.value();
    }

    private Counter newReject(final String tenant) {
        return Counter.builder("taskq.concurrency.rejected")
                .tags("tenant", tenant)
                .description("Concurrency-slot rejections per tenant")
                .register(meters);
    }
}
