package com.merakilabs.taskq.broker.postgres;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name = "taskq.broker", havingValue = "postgres", matchIfMissing = true)
public class PostgresBrokerConfig {

    @Bean
    public PostgresJobBroker postgresJobBroker(
            final NamedParameterJdbcTemplate jdbc,
            final TransactionTemplate tx,
            @Value("${taskq.worker.lease-ttl-seconds:30}") final long leaseTtlSeconds) {
        return new PostgresJobBroker(jdbc, tx, Duration.ofSeconds(leaseTtlSeconds));
    }
}
