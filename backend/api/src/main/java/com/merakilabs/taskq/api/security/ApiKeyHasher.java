package com.merakilabs.taskq.api.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of the API key. Argon2id is overkill for high-frequency lookups (every API call
 * would pay 50ms+); for the dashboard / admin operations we use Argon2id, but for the
 * service-to-service API key SHA-256 with a forced 32-byte secret is the standard pattern
 * (see GitHub PATs, Stripe keys). The key itself is 256 bits of entropy from a CSPRNG, so
 * preimage attack is infeasible without the hash.
 */
public final class ApiKeyHasher {

    private static final HexFormat HEX = HexFormat.of();

    private ApiKeyHasher() {}

    public static String hash(final String apiKey) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(md.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
