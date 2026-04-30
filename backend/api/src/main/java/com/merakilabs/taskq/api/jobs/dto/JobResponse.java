package com.merakilabs.taskq.api.jobs.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merakilabs.taskq.core.domain.Job;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;

public record JobResponse(
        UUID id,
        UUID tenantId,
        String queue,
        String type,
        String status,
        int priority,
        int attempt,
        int maxAttempts,
        Instant scheduledAt,
        Instant leasedUntil,
        String idempotencyKey,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        JsonNode payload) {

    public static JobResponse from(final Job job, final ObjectMapper mapper) {
        return new JobResponse(
                job.id().value(),
                job.tenantId().value(),
                job.queue().value(),
                job.type(),
                job.status().name(),
                job.priority().value(),
                job.attempt(),
                job.maxAttempts(),
                job.scheduledAt(),
                job.leasedUntil(),
                job.idempotencyKey(),
                job.lastError(),
                job.createdAt(),
                job.updatedAt(),
                readJson(job.payload(), mapper));
    }

    private static JsonNode readJson(final byte[] payload, final ObjectMapper mapper) {
        try {
            return mapper.readTree(payload);
        } catch (final IOException e) {
            throw new UncheckedIOException("Stored payload is not valid JSON", e);
        }
    }
}
