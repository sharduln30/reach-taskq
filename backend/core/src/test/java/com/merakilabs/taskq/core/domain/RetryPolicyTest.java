package com.merakilabs.taskq.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void delayIsAlwaysWithinJitterBand() {
        final var policy = new RetryPolicy(100, 60_000, new Random(42));
        for (int n = 0; n < 20; n++) {
            final long ms = policy.nextDelay(n).toMillis();
            assertThat(ms).isBetween(0L, 60_000L);
        }
    }

    @Test
    void delayCapsAtMax() {
        final var policy = new RetryPolicy(1_000, 5_000, new Random(0));
        for (int trial = 0; trial < 100; trial++) {
            assertThat(policy.nextDelay(20).toMillis()).isLessThanOrEqualTo(5_000);
        }
    }

    @Test
    void zeroBaseRejected() {
        assertThatThrownBy(() -> new RetryPolicy(0, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxBelowBaseRejected() {
        assertThatThrownBy(() -> new RetryPolicy(100, 50)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeAttemptRejected() {
        final var policy = new RetryPolicy(100, 1_000);
        assertThatThrownBy(() -> policy.nextDelay(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hugeAttemptDoesNotOverflow() {
        final var policy = new RetryPolicy(1_000, 60_000, new Random(1));
        final Duration d = policy.nextDelay(1_000_000);
        assertThat(d.toMillis()).isLessThanOrEqualTo(60_000);
    }
}
