package com.merakilabs.taskq.worker.runtime;

import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.TenantRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tiny TTL cache for the worker hot path. Avoids hitting Postgres for the
 * tenant row on every dispatched job. Stale entries are tolerated — the
 * tenant's RPS/concurrency knobs are operator-tuned, not safety-critical.
 */
@Component
public class TenantCache {

    private final TenantRepository tenants;
    private final long ttlMs;

    private final ConcurrentHashMap<TenantId, Entry> cache = new ConcurrentHashMap<>();

    public TenantCache(
            final TenantRepository tenants,
            @Value("${taskq.worker.tenant-cache-ttl-seconds:30}") final long ttlSeconds) {
        this.tenants = tenants;
        this.ttlMs = Duration.ofSeconds(ttlSeconds).toMillis();
    }

    public Optional<Tenant> find(final TenantId id) {
        final long now = System.currentTimeMillis();
        final Entry hit = cache.get(id);
        if (hit != null && now - hit.loadedAt < ttlMs) {
            return Optional.of(hit.tenant);
        }
        final Optional<Tenant> fresh = tenants.findById(id);
        fresh.ifPresent(t -> cache.put(id, new Entry(t, now)));
        return fresh;
    }

    public void invalidate(final TenantId id) {
        cache.remove(id);
    }

    private record Entry(Tenant tenant, long loadedAt) {}
}
