package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.Job;

/**
 * Pluggable job-type handler. The worker resolves a handler by {@link Job#type()} and invokes it.
 * Handlers MUST be idempotent against {@code (jobId, attempt)} — we deliver at-least-once.
 */
public interface JobHandler {

    String type();

    HandlerOutcome handle(Job job);
}
