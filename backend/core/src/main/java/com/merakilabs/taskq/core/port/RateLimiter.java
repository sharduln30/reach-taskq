package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.TenantId;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface RateLimiter {

    /**
     * Try to consume one token. Returns a decision describing whether the call may proceed and,
     * if not, when to retry.
     */
    Decision tryAcquire(TenantId tenantId, int rps, int burst);

    /**
     * Non-blocking variant that returns a future. Default impl wraps the synchronous call —
     * implementations backed by a true async client (Lettuce async) SHOULD override and avoid
     * holding the calling thread.
     */
    default CompletionStage<Decision> tryAcquireAsync(
            final TenantId tenantId, final int rps, final int burst) {
        return CompletableFuture.completedFuture(tryAcquire(tenantId, rps, burst));
    }

    record Decision(boolean allowed, long remaining, Duration retryAfter) {
        public static Decision allow(final long remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }

        public static Decision deny(final Duration retryAfter) {
            return new Decision(false, 0, retryAfter);
        }
    }
}
