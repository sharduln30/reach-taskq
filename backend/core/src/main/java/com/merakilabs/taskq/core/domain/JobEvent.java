package com.merakilabs.taskq.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record JobEvent(
        UUID id,
        JobId jobId,
        Type type,
        int attempt,
        String details,
        String traceId,
        Instant occurredAt) {

    public JobEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    public enum Type {
        SUBMITTED,
        SCHEDULED,
        READY,
        LEASED,
        HEARTBEAT,
        SUCCEEDED,
        FAILED,
        RETRY_SCHEDULED,
        DEAD,
        CANCELLED,
        REPLAYED,
        REAPED
    }
}
