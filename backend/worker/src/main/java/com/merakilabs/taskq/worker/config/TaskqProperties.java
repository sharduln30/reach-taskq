package com.merakilabs.taskq.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "taskq")
public record TaskqProperties(
        String broker,
        WorkerProps worker,
        OutboxProps outbox,
        SchedulerProps scheduler,
        RetryProps retry,
        IdempotencyProps idempotency,
        PayloadProps payload) {

    public record WorkerProps(int concurrency, int leaseTtlSeconds, int leaseReaperIntervalSeconds) {}

    public record OutboxProps(int pollIntervalMs, int batchSize) {}

    public record SchedulerProps(int pollIntervalMs) {}

    public record RetryProps(int defaultMaxAttempts, long backoffBaseMs, long backoffMaxMs) {}

    public record IdempotencyProps(int ttlHours) {}

    public record PayloadProps(long maxBytes) {}
}
