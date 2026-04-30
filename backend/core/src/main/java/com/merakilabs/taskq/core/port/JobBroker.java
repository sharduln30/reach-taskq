package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.TenantId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Transport-level port. Implementations: Redis Streams, Postgres SKIP-LOCKED, Kafka, etc.
 *
 * <p>The broker is responsible only for moving job ids; durable state lives in {@link JobRepository}.
 * This separation lets the same domain logic work over different transports.
 */
public interface JobBroker {

    /** Publish a ready job onto the broker so workers can pick it up. */
    void publishReady(QueueName queue, JobId jobId, TenantId tenantId);

    /** Pull up to {@code maxBatch} jobs for the given queue, blocking up to {@code blockFor}. */
    List<Delivery> pull(QueueName queue, String consumer, int maxBatch, Duration blockFor);

    /** Acknowledge a delivery. The broker may forget the message after this. */
    void ack(QueueName queue, String consumer, Delivery delivery);

    /** Reject a delivery so it is redelivered (the broker may apply its own backoff). */
    void nack(QueueName queue, String consumer, Delivery delivery);

    /** Schedule a job to be made ready at a future time. */
    void schedule(QueueName queue, JobId jobId, TenantId tenantId, java.time.Instant runAt);

    /**
     * Atomically attempt to take a lease on a job for the given visibility duration.
     *
     * @return Optional of generated lease token if the lease was acquired (no other worker holds it).
     */
    Optional<LeaseToken> tryLease(JobId jobId, Duration visibility);

    /** Renew an existing lease. Returns true if the lease was still valid and got renewed. */
    boolean renewLease(JobId jobId, LeaseToken token, Duration visibility);

    /** Release a lease (success or final failure). */
    void releaseLease(JobId jobId, LeaseToken token);

    /** Move scheduled-but-due jobs onto the ready stream. Returns count moved. */
    int moveDueScheduled(QueueName queue, int batchSize);

    /** Approximate ready-queue depth for the given queue (used for autoscaling/metrics). */
    long readyDepth(QueueName queue);

    /** Lightweight delivery handle that the broker uses to ack/nack later. */
    record Delivery(String messageId, JobId jobId, TenantId tenantId) {}
}
