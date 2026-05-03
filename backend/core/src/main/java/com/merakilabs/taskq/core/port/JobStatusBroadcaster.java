package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.TenantId;

/**
 * Push job-status changes out to live UI subscribers (WebSocket, SSE, etc.).
 *
 * <p>Replaces the previous {@code pg_notify} pipeline, which serialized every
 * status-change INSERT/UPDATE on the per-database notification queue lock and
 * dominated submit p95 under load. App-side broadcast lets us fan out to live
 * sessions without holding a Postgres backend.
 *
 * <p>Implementations MUST be non-blocking from the caller's perspective and
 * MUST never throw — broadcast failures are observability-only, not correctness.
 */
public interface JobStatusBroadcaster {

    /** No-op default useful for tests / when the API module is not wired in. */
    JobStatusBroadcaster NOOP = (jobId, tenantId, queue, status, attempt) -> {};

    void publish(JobId jobId, TenantId tenantId, QueueName queue, JobStatus status, int attempt);
}
