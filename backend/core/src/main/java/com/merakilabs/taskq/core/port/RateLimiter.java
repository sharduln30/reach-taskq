package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.TenantId;
import java.time.Duration;

public interface RateLimiter {

    /**
     * Try to consume one token. Returns a decision describing whether the call may proceed and,
     * if not, when to retry.
     */
    Decision tryAcquire(TenantId tenantId, int rps, int burst);

    record Decision(boolean allowed, long remaining, Duration retryAfter) {
        public static Decision allow(final long remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }

        public static Decision deny(final Duration retryAfter) {
            return new Decision(false, 0, retryAfter);
        }
    }
}
