package com.merakilabs.taskq.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.ConcurrencyLimiter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RedisConcurrencyLimiterTest {

    private static GenericContainer<?> redis;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;

    @BeforeAll
    static void start() {
        final String hostOverride = System.getenv("TEST_REDIS_HOST");
        final String portOverride = System.getenv("TEST_REDIS_PORT");
        final String host;
        final int port;
        if (hostOverride != null && !hostOverride.isBlank()) {
            host = hostOverride;
            port = portOverride != null ? Integer.parseInt(portOverride) : 6379;
        } else {
            redis = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine")).withExposedPorts(6379);
            redis.start();
            host = redis.getHost();
            port = redis.getMappedPort(6379);
        }
        client = RedisClient.create(
                RedisURI.builder().withHost(host).withPort(port).build());
        conn = client.connect();
    }

    @AfterAll
    static void stop() {
        if (conn != null) conn.close();
        if (client != null) client.shutdown();
        if (redis != null) redis.stop();
    }

    @AfterEach
    void flush() {
        conn.sync().flushall();
    }

    private RedisConcurrencyLimiter newLimiter(final Duration leaseTtl) {
        return new RedisConcurrencyLimiter(
                conn, new LuaScripts(), new SimpleMeterRegistry(), leaseTtl, true);
    }

    @Test
    void acquireUpToMaxThenReject() {
        final ConcurrencyLimiter cl = newLimiter(Duration.ofSeconds(30));
        final TenantId t = new TenantId(UUID.randomUUID());

        assertThat(cl.tryAcquire(t, 3, "h1")).isTrue();
        assertThat(cl.tryAcquire(t, 3, "h2")).isTrue();
        assertThat(cl.tryAcquire(t, 3, "h3")).isTrue();
        assertThat(cl.tryAcquire(t, 3, "h4")).isFalse();
        assertThat(cl.currentUsage(t)).isEqualTo(3);
    }

    @Test
    void releaseFreesSlot() {
        final ConcurrencyLimiter cl = newLimiter(Duration.ofSeconds(30));
        final TenantId t = new TenantId(UUID.randomUUID());

        assertThat(cl.tryAcquire(t, 2, "a")).isTrue();
        assertThat(cl.tryAcquire(t, 2, "b")).isTrue();
        assertThat(cl.tryAcquire(t, 2, "c")).isFalse();

        cl.release(t, "a");

        assertThat(cl.tryAcquire(t, 2, "c")).isTrue();
    }

    @Test
    void leaseExpiryAutoPrunes() throws InterruptedException {
        final ConcurrencyLimiter cl = newLimiter(Duration.ofMillis(200));
        final TenantId t = new TenantId(UUID.randomUUID());

        assertThat(cl.tryAcquire(t, 1, "stuck")).isTrue();
        assertThat(cl.tryAcquire(t, 1, "next")).isFalse();

        Thread.sleep(300);

        assertThat(cl.tryAcquire(t, 1, "next")).isTrue();
        assertThat(cl.currentUsage(t)).isEqualTo(1);
    }

    @Test
    void refreshKeepsHolderAlive() throws InterruptedException {
        final ConcurrencyLimiter cl = newLimiter(Duration.ofMillis(300));
        final TenantId t = new TenantId(UUID.randomUUID());

        assertThat(cl.tryAcquire(t, 1, "long-running")).isTrue();
        Thread.sleep(150);
        assertThat(cl.refresh(t, "long-running")).isTrue();
        Thread.sleep(200);

        assertThat(cl.tryAcquire(t, 1, "intruder"))
                .as("slot should still be held thanks to refresh")
                .isFalse();
    }

    @Test
    void refreshAfterEvictionReturnsFalse() throws InterruptedException {
        final ConcurrencyLimiter cl = newLimiter(Duration.ofMillis(150));
        final TenantId t = new TenantId(UUID.randomUUID());

        assertThat(cl.tryAcquire(t, 1, "evictable")).isTrue();
        Thread.sleep(250);
        // Force a prune by calling tryAcquire with a different token.
        cl.tryAcquire(t, 2, "newcomer");

        assertThat(cl.refresh(t, "evictable"))
                .as("an evicted holder cannot be refreshed back into the set")
                .isFalse();
    }

    @Test
    void contendedAcquire_neverExceedsMax() throws Exception {
        final ConcurrencyLimiter cl = newLimiter(Duration.ofSeconds(30));
        final TenantId t = new TenantId(UUID.randomUUID());
        final int max = 8;
        final int threads = 32;

        final var success = new java.util.concurrent.atomic.AtomicInteger();
        final var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        final var latch = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final String token = "tok-" + i;
            pool.submit(() -> {
                try {
                    if (cl.tryAcquire(t, max, token)) success.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(success.get()).isEqualTo(max);
        assertThat(cl.currentUsage(t)).isEqualTo(max);
    }
}
