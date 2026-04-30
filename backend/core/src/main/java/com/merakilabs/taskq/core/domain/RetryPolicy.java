package com.merakilabs.taskq.core.domain;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;

/**
 * Exponential backoff with full jitter.
 *
 * <p>delay(n) = min(maxMs, random(0, baseMs * 2^n))
 *
 * <p>Full jitter is preferred over equal jitter / decorrelated jitter for queue-flush scenarios:
 * it spreads retries the most evenly which reduces thundering-herd on a downstream that just
 * recovered.
 */
public final class RetryPolicy {

    private final long baseMs;
    private final long maxMs;
    private final Random random;

    public RetryPolicy(final long baseMs, final long maxMs) {
        this(baseMs, maxMs, new SecureRandom());
    }

    public RetryPolicy(final long baseMs, final long maxMs, final Random random) {
        if (baseMs <= 0) {
            throw new IllegalArgumentException("baseMs must be > 0");
        }
        if (maxMs < baseMs) {
            throw new IllegalArgumentException("maxMs must be >= baseMs");
        }
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.random = random;
    }

    public Duration nextDelay(final int attempt) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be >= 0");
        }
        final long exp;
        try {
            exp = Math.multiplyExact(baseMs, 1L << Math.min(attempt, 30));
        } catch (final ArithmeticException overflow) {
            return Duration.ofMillis(maxMs);
        }
        final long capped = Math.min(maxMs, exp);
        final long jittered = random.nextLong(capped + 1L);
        return Duration.ofMillis(jittered);
    }

    public Instant nextRunAt(final int attempt, final Instant now) {
        return now.plus(nextDelay(attempt));
    }
}
