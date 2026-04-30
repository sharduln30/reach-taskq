package com.merakilabs.taskq.core.domain;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public record LeaseToken(String value) {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 16;

    public LeaseToken {
        Objects.requireNonNull(value, "lease token must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("lease token must not be blank");
        }
    }

    public static LeaseToken generate() {
        final byte[] bytes = new byte[TOKEN_BYTES];
        RNG.nextBytes(bytes);
        return new LeaseToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    @Override
    public String toString() {
        return value;
    }
}
