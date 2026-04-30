package com.merakilabs.taskq.core.domain;

import java.time.Instant;
import java.util.Objects;

public record Tenant(
        TenantId id,
        String name,
        String apiKeyHash,
        int rateLimitRps,
        int rateLimitBurst,
        int maxConcurrency,
        boolean active,
        Instant createdAt) {

    public Tenant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(apiKeyHash, "apiKeyHash");
        Objects.requireNonNull(createdAt, "createdAt");
        if (rateLimitRps < 1) {
            throw new IllegalArgumentException("rateLimitRps must be >= 1");
        }
        if (rateLimitBurst < rateLimitRps) {
            throw new IllegalArgumentException("rateLimitBurst must be >= rateLimitRps");
        }
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
    }
}
