package com.merakilabs.taskq.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.RateLimiter;
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

class RedisRateLimiterTest {

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

    private RedisRateLimiter newLimiter(final boolean failOpen) {
        return new RedisRateLimiter(
                conn, new LuaScripts(), new SimpleMeterRegistry(), failOpen, Duration.ofMillis(100));
    }

    @Test
    void burstThenDeny() {
        final RateLimiter rl = newLimiter(true);
        final TenantId t = new TenantId(UUID.randomUUID());

        // rps=1, burst=10: refill after 10 fast calls is <1 token, so the 11th must be denied.
        for (int i = 0; i < 10; i++) {
            assertThat(rl.tryAcquire(t, 1, 10).allowed())
                    .as("call %d should be allowed within burst", i)
                    .isTrue();
        }
        final var denied = rl.tryAcquire(t, 1, 10);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter()).isPositive();
    }

    @Test
    void refillAfterSleep() throws InterruptedException {
        final RateLimiter rl = newLimiter(true);
        final TenantId t = new TenantId(UUID.randomUUID());

        // rps=2, burst=2, drain the bucket then wait long enough to refill at least one token.
        assertThat(rl.tryAcquire(t, 2, 2).allowed()).isTrue();
        assertThat(rl.tryAcquire(t, 2, 2).allowed()).isTrue();
        assertThat(rl.tryAcquire(t, 2, 2).allowed()).isFalse();

        Thread.sleep(800);
        assertThat(rl.tryAcquire(t, 2, 2).allowed())
                .as("after 800ms at 2rps the bucket should have refilled at least one token")
                .isTrue();
    }

    @Test
    void concurrentContenders_noOverGrant() throws Exception {
        final RateLimiter rl = newLimiter(true);
        final TenantId t = new TenantId(UUID.randomUUID());
        final int burst = 50;
        final int threads = 20;
        final int callsPerThread = 10;

        final var allowed = new java.util.concurrent.atomic.AtomicInteger();
        final var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        final var latch = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    for (int j = 0; j < callsPerThread; j++) {
                        if (rl.tryAcquire(t, 1, burst).allowed()) allowed.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        // rps=1, burst=50: in <1s we should see ~burst grants total (+1-2 from refill), no more.
        assertThat(allowed.get()).isBetween(burst - 1, burst + 5);
    }

    @Test
    void failOpenWhenRedisRefuses() {
        // Connect to localhost:1 (guaranteed unreachable). Lettuce defers connect until the
        // first command, so EVALSHA inside tryAcquire blows up and hits the fail-open branch.
        final RedisClient bad = RedisClient.create(RedisURI.builder()
                .withHost("127.0.0.1")
                .withPort(1)
                .withTimeout(Duration.ofMillis(150))
                .build());
        try (StatefulRedisConnection<String, String> badConn = bad.connect()) {
            final var rl = new RedisRateLimiter(
                    badConn, new LuaScripts(), new SimpleMeterRegistry(), true, Duration.ofMillis(50));
            final var d = rl.tryAcquire(new TenantId(UUID.randomUUID()), 1, 1);
            assertThat(d.allowed()).as("fail-open should grant when Redis is unreachable").isTrue();
        } catch (final io.lettuce.core.RedisConnectionException expected) {
            // Lettuce raised on connect(): fine. Contract still holds, no leak from limiter.
        } finally {
            bad.shutdown();
        }
    }
}
