package com.merakilabs.taskq.core.port;

import java.time.Duration;
import java.util.Optional;

public sealed interface HandlerOutcome {

    static HandlerOutcome success() {
        return Success.INSTANCE;
    }

    static HandlerOutcome retry(final String reason) {
        return new Retry(reason, Optional.empty());
    }

    static HandlerOutcome retryAfter(final String reason, final Duration delay) {
        return new Retry(reason, Optional.of(delay));
    }

    static HandlerOutcome fail(final String reason) {
        return new Fail(reason);
    }

    record Success() implements HandlerOutcome {
        static final Success INSTANCE = new Success();
    }

    record Retry(String reason, Optional<Duration> delayHint) implements HandlerOutcome {}

    record Fail(String reason) implements HandlerOutcome {}
}
