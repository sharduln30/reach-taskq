package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository {

    Job insert(Job job);

    Optional<Job> findById(JobId id);

    List<Job> findByTenant(TenantId tenantId, Optional<JobStatus> status, int limit, int offset);

    /**
     * Conditional update used by the worker on lease/ack/fail. The expected status guards
     * against double-processing across the lease-reaper boundary.
     *
     * @return true if the row was updated.
     */
    boolean transition(JobId id, JobStatus expected, JobStatus next, String error);

    /** Mark job leased. Returns false if the row was not in READY state. */
    boolean lease(JobId id, LeaseToken token, Instant leasedUntil);

    /** Renew lease watermark in the durable store. */
    boolean renewLease(JobId id, LeaseToken token, Instant newLeasedUntil);

    /** Mark scheduled retry. */
    boolean scheduleRetry(JobId id, Instant runAt, int newAttempt, String lastError);

    /** Mark dead-lettered. The {@code finalAttempt} value is the attempt that exhausted retries. */
    boolean markDead(JobId id, int finalAttempt, String lastError);

    /** Find jobs whose lease has expired so the reaper can republish them. */
    List<Job> findExpiredLeases(Instant now, int limit);

    /** Reset an expired-lease row back to READY (used by reaper). */
    boolean reapExpired(JobId id, LeaseToken expectedToken);

    /** Find scheduled jobs whose runAt has passed. */
    List<Job> findDueScheduled(Instant now, QueueName queue, int limit);

    /** Promote a scheduled job to READY (atomic state move). */
    boolean promoteScheduled(JobId id);

    /** Stats for dashboard / autoscaling. */
    long countByStatus(JobStatus status, Optional<TenantId> tenantId, Optional<QueueName> queue);
}
