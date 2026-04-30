package com.merakilabs.taskq.persistence;

import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.Outbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOutbox implements Outbox {

    private static final String INSERT_SQL =
            """
            INSERT INTO outbox (id, type, queue, job_id, tenant_id, run_at, created_at)
            VALUES (:id, CAST(:type AS outbox_event_type), :queue, :job_id, :tenant_id, :run_at, :created_at)
            """;

    /**
     * SKIP-LOCKED: multiple relay workers may run concurrently; each takes a disjoint batch.
     * The selected rows are not yet marked published — that happens after a successful publish.
     */
    private static final String POLL_SQL =
            """
            SELECT id, type, queue, job_id, tenant_id, run_at, created_at
              FROM outbox
             WHERE published_at IS NULL
             ORDER BY created_at ASC
             FOR UPDATE SKIP LOCKED
             LIMIT :lim
            """;

    private static final String MARK_PUBLISHED_SQL =
            "UPDATE outbox SET published_at = now() WHERE id IN (:ids)";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcOutbox(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID enqueue(
            final EventType type,
            final QueueName queue,
            final JobId jobId,
            final TenantId tenantId,
            final Instant runAt) {
        final UUID id = UUID.randomUUID();
        final var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("type", type.name())
                .addValue("queue", queue.value())
                .addValue("job_id", jobId.value())
                .addValue("tenant_id", tenantId.value())
                .addValue("run_at", Timestamp.from(runAt))
                .addValue("created_at", Timestamp.from(Instant.now()));
        jdbc.update(INSERT_SQL, params);
        return id;
    }

    @Override
    public List<Pending> pollUnpublished(final int batchSize) {
        return jdbc.query(POLL_SQL, new MapSqlParameterSource("lim", batchSize), (rs, rowNum) -> new Pending(
                rs.getObject("id", UUID.class),
                EventType.valueOf(rs.getString("type")),
                new QueueName(rs.getString("queue")),
                new JobId(rs.getObject("job_id", UUID.class)),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                rs.getTimestamp("run_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()));
    }

    @Override
    public void markPublished(final List<UUID> outboxIds) {
        if (outboxIds.isEmpty()) {
            return;
        }
        jdbc.update(MARK_PUBLISHED_SQL, new MapSqlParameterSource("ids", outboxIds));
    }
}
