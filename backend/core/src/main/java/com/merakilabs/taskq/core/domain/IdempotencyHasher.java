package com.merakilabs.taskq.core.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable SHA-256 of the submission's salient fields. The hash protects against the case where
 * a buggy client reuses an idempotency key with a *different* payload — we return 422 instead
 * of silently accepting the new request.
 */
public final class IdempotencyHasher {

    private static final HexFormat HEX = HexFormat.of();

    private IdempotencyHasher() {}

    public static String hash(final SubmitJobCommand cmd) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(cmd.tenantId().toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(cmd.queue().value().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(cmd.type().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(cmd.payload());
            return HEX.formatHex(md.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
