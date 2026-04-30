package com.merakilabs.taskq.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyHasherTest {

    @Test
    void samePayloadHashesSame() {
        final var cmd = sample(new byte[] {1, 2, 3});
        assertThat(IdempotencyHasher.hash(cmd)).isEqualTo(IdempotencyHasher.hash(cmd));
    }

    @Test
    void differentPayloadHashesDifferent() {
        assertThat(IdempotencyHasher.hash(sample(new byte[] {1, 2, 3})))
                .isNotEqualTo(IdempotencyHasher.hash(sample(new byte[] {1, 2, 4})));
    }

    @Test
    void differentTenantHashesDifferent() {
        final var a = new SubmitJobCommand(
                new TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                QueueName.DEFAULT,
                "send-email",
                new byte[] {1},
                Priority.NORMAL,
                3,
                Optional.empty(),
                Optional.empty());
        final var b = new SubmitJobCommand(
                new TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                QueueName.DEFAULT,
                "send-email",
                new byte[] {1},
                Priority.NORMAL,
                3,
                Optional.empty(),
                Optional.empty());
        assertThat(IdempotencyHasher.hash(a)).isNotEqualTo(IdempotencyHasher.hash(b));
    }

    private static SubmitJobCommand sample(final byte[] payload) {
        return new SubmitJobCommand(
                new TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                QueueName.DEFAULT,
                "send-email",
                payload,
                Priority.NORMAL,
                3,
                Optional.empty(),
                Optional.empty());
    }
}
