package com.merakilabs.taskq.api.jobs.dto;

import com.merakilabs.taskq.core.domain.JobEvent;
import java.time.Instant;
import java.util.UUID;

public record JobEventResponse(
        UUID id,
        UUID jobId,
        String type,
        int attempt,
        String details,
        String traceId,
        Instant occurredAt) {

    public static JobEventResponse from(final JobEvent e) {
        return new JobEventResponse(
                e.id(), e.jobId().value(), e.type().name(), e.attempt(), e.details(), e.traceId(), e.occurredAt());
    }
}
