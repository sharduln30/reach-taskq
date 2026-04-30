package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox port. The submit-path inserts an outbox row in the SAME transaction
 * as the job + idempotency-key insert. A relay polls unpublished rows and publishes them
 * to the broker, marking them published on success.
 *
 * <p>Guarantees: at-least-once publish. Consumers MUST tolerate duplicates (we do, via the
 * idempotency-key + LEASED-state checks).
 */
public interface Outbox {

    UUID enqueue(EventType type, QueueName queue, JobId jobId, TenantId tenantId, Instant runAt);

    List<Pending> pollUnpublished(int batchSize);

    void markPublished(List<UUID> outboxIds);

    enum EventType {
        PUBLISH_READY,
        SCHEDULE
    }

    record Pending(
            UUID id,
            EventType type,
            QueueName queue,
            JobId jobId,
            TenantId tenantId,
            Instant runAt,
            Instant createdAt) {}
}
