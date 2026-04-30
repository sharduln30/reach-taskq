package com.merakilabs.taskq.core.domain;

public sealed interface SubmitResult {

    record Created(Job job) implements SubmitResult {}

    record IdempotentReplay(Job existingJob) implements SubmitResult {}

    record IdempotencyConflict(JobId existingJobId, String reason) implements SubmitResult {}

    record RateLimited(java.time.Duration retryAfter) implements SubmitResult {}

    record PayloadTooLarge(long limitBytes) implements SubmitResult {}

    record TenantInactive() implements SubmitResult {}
}
