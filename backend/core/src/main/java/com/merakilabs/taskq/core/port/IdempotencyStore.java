package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.TenantId;
import java.time.Instant;
import java.util.Optional;

/**
 * Idempotency lookup for job submission. Implementations MUST persist the mapping atomically
 * with the job itself (single transaction), otherwise a crash between "insert job" and
 * "insert idempotency key" would let a retried client submit a duplicate.
 */
public interface IdempotencyStore {

    /**
     * Look up an existing record for the given key. Used as a short-circuit during submit.
     *
     * @param requestHash hash of the request body, implementations MAY reject mismatches.
     */
    Optional<Lookup> find(TenantId tenantId, String key, String requestHash);

    record Lookup(JobId jobId, String requestHash, Instant expiresAt, boolean hashMismatch) {}
}
