package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.TenantId;

/**
 * Per-tenant fleet-wide semaphore. Holders are identified by an opaque token
 * (we reuse the worker lease token) so {@link #release} is idempotent and a
 * crashed worker's slot is auto-pruned when its TTL elapses.
 */
public interface ConcurrencyLimiter {

    /**
     * Try to claim a concurrency slot for the holder identified by {@code holderToken}.
     * Returns true if a slot was claimed; false if the tenant is already at capacity.
     */
    boolean tryAcquire(TenantId tenantId, int maxConcurrency, String holderToken);

    /** Release the slot held by {@code holderToken}. No-op if already released or expired. */
    void release(TenantId tenantId, String holderToken);

    /**
     * Refresh the holder's TTL, called from the worker's heartbeat so a long-running job
     * doesn't get auto-pruned. Returns false if the holder was already evicted (worker
     * should treat that as "lost the slot" and yield).
     */
    boolean refresh(TenantId tenantId, String holderToken);

    /** Current usage (after pruning expired holders). For metrics + dashboard. */
    long currentUsage(TenantId tenantId);
}
