package com.merakilabs.taskq.broker.redis;

import com.merakilabs.taskq.core.port.JobRepository;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Redis {@code RedisClient} + {@code StatefulRedisConnection} beans are
 * owned by {@code RatelimitConfig} (always present) and shared with the broker
 * here when {@code TASKQ_BROKER=redis}. This avoids two configs registering a
 * bean named {@code redisClient}.
 */
@Configuration
@ConditionalOnProperty(name = "taskq.broker", havingValue = "redis")
public class RedisBrokerConfig {

    @Bean
    public RedisJobBroker redisJobBroker(
            final StatefulRedisConnection<String, String> connection,
            final JobRepository jobs,
            @Value("${taskq.worker.lease-ttl-seconds:30}") final long leaseTtlSeconds) {
        return new RedisJobBroker(connection, jobs, Duration.ofSeconds(leaseTtlSeconds));
    }
}
