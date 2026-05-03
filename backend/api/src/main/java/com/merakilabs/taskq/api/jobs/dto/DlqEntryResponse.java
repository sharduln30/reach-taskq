package com.merakilabs.taskq.api.jobs.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merakilabs.taskq.core.domain.Job;
import java.time.Instant;
import java.util.UUID;

/**
 * Page row for {@code GET /v1/dlq}. It is the standard {@link JobResponse} fields
 * plus the {@code dlq_reasons} columns (reason + dead_at) so the UI can show a
 * structured failure cause distinct from the latest {@code last_error}.
 */
public record DlqEntryResponse(
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
        JsonNode payload,
        String reason,
        Instant deadAt) {

    public static DlqEntryResponse from(
            final Job job, final ObjectMapper mapper, final Instant deadAt, final String reason) {
        final JobResponse base = JobResponse.from(job, mapper);
        return new DlqEntryResponse(
                base.id(),
                base.tenantId(),
                base.queue(),
                base.type(),
                base.status(),
                base.priority(),
                base.attempt(),
                base.maxAttempts(),
                base.scheduledAt(),
                base.leasedUntil(),
                base.idempotencyKey(),
                base.lastError(),
                base.createdAt(),
                base.updatedAt(),
                base.payload(),
                reason,
                deadAt);
    }
}
