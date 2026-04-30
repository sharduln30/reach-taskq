package com.merakilabs.taskq.core.domain;

import java.util.Objects;
import java.util.UUID;

public record TenantId(UUID value) {
    public TenantId {
        Objects.requireNonNull(value, "TenantId value must not be null");
    }

    public static TenantId of(final String s) {
        return new TenantId(UUID.fromString(s));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
