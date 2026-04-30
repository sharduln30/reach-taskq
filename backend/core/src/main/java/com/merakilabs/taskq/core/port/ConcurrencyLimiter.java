package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.TenantId;

public interface ConcurrencyLimiter {

    /** Try to claim a concurrency slot. Returns true if claimed. */
    boolean tryAcquire(TenantId tenantId, int maxConcurrency);

    /** Release a concurrency slot. */
    void release(TenantId tenantId);

    /** Current usage (for metrics + dashboard). */
    long currentUsage(TenantId tenantId);
}
