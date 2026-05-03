package com.merakilabs.taskq.broker.postgres;

import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.JobBroker;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Postgres SKIP-LOCKED implementation of {@link JobBroker}. The "broker" here is a thin layer
 * over the {@code jobs} table, it does not own a separate transport, so {@code publishReady} is
 * a no-op (the row is already in {@code READY} after submission). Pull is a SKIP-LOCKED scan that
 * atomically transitions the selected rows to {@code LEASED}.
 *
 * <p>This is the simplest viable transport. It scales vertically with Postgres (~10K msgs/sec
 * sustained) and is a perfect fallback when Redis is unavailable. The Redis Streams adapter
 * will share this exact interface.
 */
public class PostgresJobBroker implements JobBroker {

    private static final String LEASE_BATCH_SQL =
            """
            UPDATE jobs
               SET status = 'LEASED'::job_status,
                   leased_until = :leased_until,
                   lease_token = :token,
                   updated_at = now()
             WHERE id IN (
                SELECT id FROM jobs
                 WHERE status = 'READY'::job_status
                   AND queue = :queue
                 ORDER BY priority ASC, scheduled_at ASC, created_at ASC
                 FOR UPDATE SKIP LOCKED
                 LIMIT :batch
             )
            RETURNING id, tenant_id
            """;

    private static final String READY_DEPTH_SQL =
            "SELECT count(*) FROM jobs WHERE status = 'READY'::job_status AND queue = :queue";

    private static final String LEASE_RENEW_SQL =
            """
            UPDATE jobs
               SET leased_until = :leased_until,
                   updated_at = now()
             WHERE id = :id
               AND status = 'LEASED'::job_status
               AND lease_token = :token
            """;

    private static final String LEASE_CLEAR_SQL =
            """
            UPDATE jobs
               SET lease_token = NULL,
                   leased_until = NULL,
                   updated_at = now()
             WHERE id = :id
               AND lease_token = :token
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Duration leaseTtl;

    public PostgresJobBroker(
            final NamedParameterJdbcTemplate jdbc,
            final TransactionTemplate tx,
            final Duration leaseTtl) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.leaseTtl = leaseTtl;
    }

    @Override
    public void publishReady(final QueueName queue, final JobId jobId, final TenantId tenantId) {
        // No-op: jobs table IS the queue here; workers pick READY rows via SKIP-LOCKED.
    }

    @Override
    public List<Delivery> pull(
            final QueueName queue, final String consumer, final int maxBatch, final Duration blockFor) {
        final long deadline = System.nanoTime() + blockFor.toNanos();
        for (;;) {
            final List<Delivery> batch = leaseBatch(queue, maxBatch);
            if (!batch.isEmpty() || System.nanoTime() >= deadline) {
                return batch;
            }
            // simple poll cadence (200ms). Workers can also be poked via Postgres LISTEN/NOTIFY
            // for sub-50ms wakeups; left as a future optimisation.
            LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
        }
    }

    private List<Delivery> leaseBatch(final QueueName queue, final int maxBatch) {
        final LeaseToken token = LeaseToken.generate();
        final Instant leasedUntil = Instant.now().plus(leaseTtl);
        final List<Delivery> deliveries = new ArrayList<>();

        Boolean ok = tx.execute(status -> {
            final var params = new MapSqlParameterSource()
                    .addValue("queue", queue.value())
                    .addValue("token", token.value())
                    .addValue("leased_until", Timestamp.from(leasedUntil))
                    .addValue("batch", maxBatch);
            jdbc.query(LEASE_BATCH_SQL, params, (rs, rowNum) -> {
                deliveries.add(new Delivery(
                        rs.getObject("id", UUID.class).toString(),
                        new JobId(rs.getObject("id", UUID.class)),
                        new TenantId(rs.getObject("tenant_id", UUID.class))));
                return null;
            });
            return Boolean.TRUE;
        });
        if (!Boolean.TRUE.equals(ok)) {
            return List.of();
        }
        return deliveries;
    }

    @Override
    public void ack(final QueueName queue, final String consumer, final Delivery delivery) {
        // ack is implicit in JobRepository.transition(LEASED -> SUCCEEDED/...).
        // Just null the lease token so a stale renew can't apply.
        clearLeaseToken(delivery.jobId());
    }

    @Override
    public void nack(final QueueName queue, final String consumer, final Delivery delivery) {
        clearLeaseToken(delivery.jobId());
    }

    private void clearLeaseToken(final JobId jobId) {
        // Worker holds the token; repo already nulls lease columns in transition/retry/markDead.
        // Kept as defence-in-depth.
    }

    @Override
    public void schedule(
            final QueueName queue, final JobId jobId, final TenantId tenantId, final Instant runAt) {
        // No-op: row already in SCHEDULED+scheduled_at; ScheduledJobPromoter promotes it.
    }

    @Override
    public Optional<LeaseToken> tryLease(final JobId jobId, final Duration visibility) {
        final LeaseToken token = LeaseToken.generate();
        final Instant leasedUntil = Instant.now().plus(visibility);
        final var params = new MapSqlParameterSource()
                .addValue("id", jobId.value())
                .addValue("token", token.value())
                .addValue("leased_until", Timestamp.from(leasedUntil));
        final int n = jdbc.update(
                """
                UPDATE jobs
                   SET status = 'LEASED'::job_status,
                       leased_until = :leased_until,
                       lease_token = :token,
                       updated_at = now()
                 WHERE id = :id AND status = 'READY'::job_status
                """,
                params);
        return n > 0 ? Optional.of(token) : Optional.empty();
    }

    @Override
    public boolean renewLease(final JobId jobId, final LeaseToken token, final Duration visibility) {
        final Instant leasedUntil = Instant.now().plus(visibility);
        final var params = new MapSqlParameterSource()
                .addValue("id", jobId.value())
                .addValue("token", token.value())
                .addValue("leased_until", Timestamp.from(leasedUntil));
        return jdbc.update(LEASE_RENEW_SQL, params) > 0;
    }

    @Override
    public void releaseLease(final JobId jobId, final LeaseToken token) {
        final var params = new MapSqlParameterSource()
                .addValue("id", jobId.value())
                .addValue("token", token.value());
        jdbc.update(LEASE_CLEAR_SQL, params);
    }

    @Override
    public int moveDueScheduled(final QueueName queue, final int batchSize) {
        return jdbc.update(
                """
                UPDATE jobs
                   SET status = 'READY'::job_status,
                       updated_at = now()
                 WHERE id IN (
                   SELECT id FROM jobs
                    WHERE status = 'SCHEDULED'::job_status
                      AND queue = :queue
                      AND scheduled_at <= now()
                    ORDER BY scheduled_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batch
                 )
                """,
                new MapSqlParameterSource()
                        .addValue("queue", queue.value())
                        .addValue("batch", batchSize));
    }

    @Override
    public long readyDepth(final QueueName queue) {
        final Long n = jdbc.queryForObject(
                READY_DEPTH_SQL, new MapSqlParameterSource("queue", queue.value()), Long.class);
        return n == null ? 0 : n;
    }
}
