package com.merakilabs.taskq.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record SubmitJobCommand(
        TenantId tenantId,
        QueueName queue,
        String type,
        byte[] payload,
        Priority priority,
        int maxAttempts,
        Optional<Instant> runAt,
        Optional<String> idempotencyKey) {

    public SubmitJobCommand {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(runAt, "runAt");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }
}
