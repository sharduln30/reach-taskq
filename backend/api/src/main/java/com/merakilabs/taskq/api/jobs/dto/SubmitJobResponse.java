package com.merakilabs.taskq.api.jobs.dto;

import java.time.Instant;
import java.util.UUID;

public record SubmitJobResponse(
        UUID id,
        String status,
        boolean idempotentReplay,
        Instant scheduledAt) {}
