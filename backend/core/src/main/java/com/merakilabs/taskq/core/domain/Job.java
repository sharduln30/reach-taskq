package com.merakilabs.taskq.core.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record Job(
        JobId id,
        TenantId tenantId,
        QueueName queue,
        String type,
        byte[] payload,
        JobStatus status,
        Priority priority,
        int attempt,
        int maxAttempts,
        Instant scheduledAt,
        Instant leasedUntil,
        LeaseToken leaseToken,
        String idempotencyKey,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public Job {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(scheduledAt, "scheduledAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be >= 0");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
    }

    public Optional<Instant> leasedUntilOpt() {
        return Optional.ofNullable(leasedUntil);
    }

    public Optional<LeaseToken> leaseTokenOpt() {
        return Optional.ofNullable(leaseToken);
    }

    public Optional<String> idempotencyKeyOpt() {
        return Optional.ofNullable(idempotencyKey);
    }

    public Optional<String> lastErrorOpt() {
        return Optional.ofNullable(lastError);
    }

    public boolean attemptsExhausted() {
        return attempt >= maxAttempts;
    }
}
