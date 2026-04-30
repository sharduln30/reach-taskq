package com.merakilabs.taskq.api.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Listens on the {@code taskq_job_status} Postgres channel (fired by the {@code notify_job_status}
 * trigger on the {@code jobs} table) and turns each NOTIFY into a {@link JobStatusEvent} broadcast
 * to all matching WebSocket sessions.
 *
 * <p>Architecture choice: a dedicated long-lived JDBC connection runs the LISTEN. We do NOT use
 * Spring's data source because LISTEN ties up a connection forever and HikariCP's leak detection
 * would fire. We borrow a single Connection from the underlying DataSource and never return it
 * until shutdown.
 */
@Component
public class PostgresJobEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresJobEventListener.class);
    private static final String CHANNEL = "taskq_job_status";
    private static final long POLL_MS = 200L;

    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final JobEventBroadcaster broadcaster;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("pg-listener").factory());
    private volatile Connection connection;

    public PostgresJobEventListener(
            final DataSource dataSource,
            final ObjectMapper mapper,
            final JobEventBroadcaster broadcaster) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    void start() {
        running.set(true);
        executor.submit(this::loop);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        executor.shutdownNow();
        if (connection != null) {
            try {
                connection.close();
            } catch (final Exception ignored) {
            }
        }
    }

    private void loop() {
        while (running.get()) {
            try (final Connection c = dataSource.getConnection()) {
                this.connection = c;
                try (final Statement s = c.createStatement()) {
                    s.execute("LISTEN " + CHANNEL);
                }
                final PGConnection pg = c.unwrap(PGConnection.class);
                LOG.info("PostgresJobEventListener LISTEN on {} active", CHANNEL);
                while (running.get() && !c.isClosed()) {
                    final PGNotification[] notifications = pg.getNotifications((int) POLL_MS);
                    if (notifications == null) {
                        continue;
                    }
                    for (final PGNotification n : notifications) {
                        handlePayload(n.getParameter());
                    }
                }
            } catch (final Exception e) {
                if (!running.get()) {
                    return;
                }
                LOG.warn("LISTEN connection broke, retrying in 1s: {}", e.getMessage());
                sleepQuietly(1_000);
            }
        }
    }

    private void handlePayload(final String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        try {
            final JsonNode n = mapper.readTree(payload);
            final UUID jobId = UUID.fromString(n.get("job_id").asText());
            final UUID tenantId = UUID.fromString(n.get("tenant_id").asText());
            final String queue = n.get("queue").asText();
            final String status = n.get("status").asText();
            final int attempt = n.has("attempt") ? n.get("attempt").asInt() : 0;
            // The trigger emits Postgres-formatted TIMESTAMPTZ which is not ISO-8601 strict,
            // so we use the receive time. Sub-millisecond drift is irrelevant for a dashboard.
            broadcaster.publishStatusChange(
                    new JobStatusEvent(jobId, tenantId, queue, status, attempt, Instant.now()));
        } catch (final Exception e) {
            LOG.warn("Bad NOTIFY payload: {}", payload, e);
        }
    }

    private void sleepQuietly(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
