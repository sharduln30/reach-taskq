package com.merakilabs.taskq.core.domain;

import java.util.Objects;
import java.util.UUID;

public record JobId(UUID value) {
    public JobId {
        Objects.requireNonNull(value, "JobId value must not be null");
    }

    public static JobId random() {
        return new JobId(UUID.randomUUID());
    }

    public static JobId of(final String s) {
        return new JobId(UUID.fromString(s));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
