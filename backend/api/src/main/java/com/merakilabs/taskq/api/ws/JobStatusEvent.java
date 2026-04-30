package com.merakilabs.taskq.api.ws;

import java.time.Instant;
import java.util.UUID;

public record JobStatusEvent(
        UUID jobId, UUID tenantId, String queue, String status, int attempt, Instant updatedAt) {}
