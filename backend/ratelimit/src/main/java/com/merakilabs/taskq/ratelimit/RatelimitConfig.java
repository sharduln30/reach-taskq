package com.merakilabs.taskq.ratelimit;

import com.merakilabs.taskq.core.port.ConcurrencyLimiter;
import com.merakilabs.taskq.core.port.RateLimiter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Redis-backed limiters. The {@code RedisClient} bean is
 * {@code ConditionalOnMissingBean} so it co-exists with
 * {@code RedisBrokerConfig} when {@code TASKQ_BROKER=redis} (both share one
 * client) and is created on demand when the broker is Postgres.
 */
@Configuration
public class RatelimitConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public RedisClient redisClient(
            @Value("${taskq.redis.host:localhost}") final String host,
            @Value("${taskq.redis.port:6379}") final int port,
            @Value("${taskq.redis.password:}") final String password) {
        final RedisURI.Builder b = RedisURI.builder().withHost(host).withPort(port);
        if (password != null && !password.isBlank()) {
            b.withPassword(password.toCharArray());
        }
        return RedisClient.create(b.build());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public StatefulRedisConnection<String, String> redisConnection(final RedisClient client) {
        return client.connect();
    }

    @Bean
    public LuaScripts luaScripts() {
        return new LuaScripts();
    }

    @Bean
    @ConditionalOnProperty(name = "taskq.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
    public RateLimiter redisRateLimiter(
            final StatefulRedisConnection<String, String> conn,
            final LuaScripts scripts,
            final MeterRegistry meters,
            @Value("${taskq.ratelimit.fail-open:true}") final boolean failOpen,
            @Value("${taskq.ratelimit.redis-timeout-ms:50}") final long timeoutMs) {
        return new RedisRateLimiter(conn, scripts, meters, failOpen, Duration.ofMillis(timeoutMs));
    }

    @Bean
    @ConditionalOnProperty(name = "taskq.concurrency.enabled", havingValue = "true", matchIfMissing = true)
    public ConcurrencyLimiter redisConcurrencyLimiter(
            final StatefulRedisConnection<String, String> conn,
            final LuaScripts scripts,
            final MeterRegistry meters,
            @Value("${taskq.worker.lease-ttl-seconds:30}") final long leaseTtlSeconds,
            @Value("${taskq.ratelimit.fail-open:true}") final boolean failOpen) {
        return new RedisConcurrencyLimiter(
                conn, scripts, meters, Duration.ofSeconds(leaseTtlSeconds), failOpen);
    }
}
