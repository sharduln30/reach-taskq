package com.merakilabs.taskq.persistence;

import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.JobRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJobRepository implements JobRepository {

    private static final String INSERT_SQL =
            """
            INSERT INTO jobs
              (id, tenant_id, queue, type, payload, status, priority, attempt, max_attempts,
               scheduled_at, leased_until, lease_token, idempotency_key, last_error,
               created_at, updated_at)
            VALUES
              (:id, :tenant_id, :queue, :type, :payload, CAST(:status AS job_status),
               :priority, :attempt, :max_attempts, :scheduled_at, :leased_until,
               :lease_token, :idempotency_key, :last_error, :created_at, :updated_at)
            """;

    private static final String SELECT_BY_ID = "SELECT * FROM jobs WHERE id = :id";

    private static final String LEASE_SQL =
            """
            UPDATE jobs
               SET status = 'LEASED'::job_status,
                   leased_until = :leased_until,
                   lease_token = :token,
                   updated_at = now()
             WHERE id = :id AND status = 'READY'::job_status
            """;

    private static final String RENEW_LEASE_SQL =
            """
            UPDATE jobs
               SET leased_until = :leased_until,
                   updated_at = now()
             WHERE id = :id AND status = 'LEASED'::job_status AND lease_token = :token
            """;

    private static final String REAP_EXPIRED_SQL =
            """
            UPDATE jobs
               SET status = 'READY'::job_status,
                   leased_until = NULL,
                   lease_token = NULL,
                   attempt = attempt + 1,
                   updated_at = now()
             WHERE id = :id
               AND status = 'LEASED'::job_status
               AND lease_token = :token
               AND leased_until < now()
            """;

    private static final String FIND_EXPIRED_LEASES_SQL =
            """
            SELECT * FROM jobs
             WHERE status = 'LEASED'::job_status AND leased_until < :now
             ORDER BY leased_until ASC
             LIMIT :lim
            """;

    private static final String FIND_DUE_SCHEDULED_SQL =
            """
            SELECT * FROM jobs
             WHERE status = 'SCHEDULED'::job_status AND scheduled_at <= :now AND queue = :queue
             ORDER BY scheduled_at ASC
             LIMIT :lim
            """;

    private static final String PROMOTE_SCHEDULED_SQL =
            """
            UPDATE jobs
               SET status = 'READY'::job_status,
                   updated_at = now()
             WHERE id = :id AND status = 'SCHEDULED'::job_status
            """;

    private static final String SCHEDULE_RETRY_SQL =
            """
            UPDATE jobs
               SET status = 'SCHEDULED'::job_status,
                   scheduled_at = :run_at,
                   attempt = :attempt,
                   leased_until = NULL,
                   lease_token = NULL,
                   last_error = :error,
                   updated_at = now()
             WHERE id = :id AND status IN ('LEASED'::job_status,'FAILED'::job_status)
            """;

    private static final String MARK_DEAD_SQL =
            """
            UPDATE jobs
               SET status = 'DEAD'::job_status,
                   attempt = :attempt,
                   leased_until = NULL,
                   lease_token = NULL,
                   last_error = :error,
                   updated_at = now()
             WHERE id = :id AND status IN ('LEASED'::job_status,'FAILED'::job_status,'READY'::job_status)
            """;

    private static final String UPSERT_DLQ_REASON_SQL =
            """
            INSERT INTO dlq_reasons (job_id, reason, last_error, final_attempt, dead_at)
            VALUES (:id, :reason, :error, :attempt, now())
            ON CONFLICT (job_id) DO UPDATE
              SET reason = EXCLUDED.reason,
                  last_error = EXCLUDED.last_error,
                  final_attempt = EXCLUDED.final_attempt,
                  dead_at = EXCLUDED.dead_at
            """;

    private static final String TRANSITION_SQL =
            """
            UPDATE jobs
               SET status = CAST(:next AS job_status),
                   last_error = COALESCE(:error, last_error),
                   updated_at = now()
             WHERE id = :id AND status = CAST(:expected AS job_status)
            """;

    private static final String COUNT_BY_STATUS =
            """
            SELECT count(*) FROM jobs
             WHERE status = CAST(:status AS job_status)
               AND (CAST(:tenant AS uuid) IS NULL OR tenant_id = CAST(:tenant AS uuid))
               AND (CAST(:queue AS varchar) IS NULL OR queue = CAST(:queue AS varchar))
            """;

    private static final String FIND_BY_TENANT_NO_STATUS =
            """
            SELECT * FROM jobs
             WHERE tenant_id = :tenant
             ORDER BY created_at DESC
             LIMIT :lim OFFSET :off
            """;

    private static final String FIND_BY_TENANT_AND_STATUS =
            """
            SELECT * FROM jobs
             WHERE tenant_id = :tenant AND status = CAST(:status AS job_status)
             ORDER BY created_at DESC
             LIMIT :lim OFFSET :off
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcJobRepository(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Job insert(final Job job) {
        final var params = new MapSqlParameterSource()
                .addValue("id", job.id().value())
                .addValue("tenant_id", job.tenantId().value())
                .addValue("queue", job.queue().value())
                .addValue("type", job.type())
                .addValue("payload", job.payload())
                .addValue("status", job.status().name())
                .addValue("priority", job.priority().value())
                .addValue("attempt", job.attempt())
                .addValue("max_attempts", job.maxAttempts())
                .addValue("scheduled_at", Timestamp.from(job.scheduledAt()))
                .addValue("leased_until", job.leasedUntil() == null ? null : Timestamp.from(job.leasedUntil()))
                .addValue("lease_token", job.leaseTokenOpt().map(LeaseToken::value).orElse(null))
                .addValue("idempotency_key", job.idempotencyKey())
                .addValue("last_error", job.lastError())
                .addValue("created_at", Timestamp.from(job.createdAt()))
                .addValue("updated_at", Timestamp.from(job.updatedAt()));
        jdbc.update(INSERT_SQL, params);
        return job;
    }

    @Override
    public Optional<Job> findById(final JobId id) {
        try {
            return Optional.of(jdbc.queryForObject(
                    SELECT_BY_ID, new MapSqlParameterSource("id", id.value()), RowMappers.JOB));
        } catch (final EmptyResultDataAccessException notFound) {
            return Optional.empty();
        }
    }

    @Override
    public List<Job> findByTenant(
            final TenantId tenantId,
            final Optional<JobStatus> status,
            final int limit,
            final int offset) {
        final var params = new MapSqlParameterSource()
                .addValue("tenant", tenantId.value())
                .addValue("lim", limit)
                .addValue("off", offset);
        if (status.isEmpty()) {
            return jdbc.query(FIND_BY_TENANT_NO_STATUS, params, RowMappers.JOB);
        }
        params.addValue("status", status.get().name());
        return jdbc.query(FIND_BY_TENANT_AND_STATUS, params, RowMappers.JOB);
    }

    @Override
    public boolean transition(
            final JobId id, final JobStatus expected, final JobStatus next, final String error) {
        final var params = new MapSqlParameterSource()
                .addValue("id", id.value())
                .addValue("expected", expected.name())
                .addValue("next", next.name())
                .addValue("error", error);
        return jdbc.update(TRANSITION_SQL, params) > 0;
    }

    @Override
    public boolean lease(final JobId id, final LeaseToken token, final Instant leasedUntil) {
        final var params = new MapSqlParameterSource()
                .addValue("id", id.value())
                .addValue("token", token.value())
                .addValue("leased_until", Timestamp.from(leasedUntil));
        return jdbc.update(LEASE_SQL, params) > 0;
    }

    @Override
    public boolean renewLease(final JobId id, final LeaseToken token, final Instant newLeasedUntil) {
        final var params = new MapSqlParameterSource()
                .addValue("id", id.value())
                .addValue("token", token.value())
                .addValue("leased_until", Timestamp.from(newLeasedUntil));
        return jdbc.update(RENEW_LEASE_SQL, params) > 0;
    }

    @Override
    public boolean scheduleRetry(
            final JobId id, final Instant runAt, final int newAttempt, final String lastError) {
        final var params = new MapSqlParameterSource()
                .addValue("id", id.value())
                .addValue("run_at", Timestamp.from(runAt))
                .addValue("attempt", newAttempt)
                .addValue("error", lastError);
        return jdbc.update(SCHEDULE_RETRY_SQL, params) > 0;
    }

    @Override
    public boolean markDead(final JobId id, final int finalAttempt, final String lastError) {
        final var params = new MapSqlParameterSource()
                .addValue("id", id.value())
                .addValue("attempt", finalAttempt)
                .addValue("error", lastError);
        final boolean updated = jdbc.update(MARK_DEAD_SQL, params) > 0;
        if (updated) {
            final var dlqParams = new MapSqlParameterSource()
                    .addValue("id", id.value())
                    .addValue("reason", lastError == null ? "unknown" : lastError)
                    .addValue("error", lastError)
                    .addValue("attempt", finalAttempt);
            jdbc.update(UPSERT_DLQ_REASON_SQL, dlqParams);
        }
        return updated;
    }

    @Override
    public List<Job> findExpiredLeases(final Instant now, final int limit) {
        final SqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("lim", limit);
        return jdbc.query(FIND_EXPIRED_LEASES_SQL, params, RowMappers.JOB);
    }

    @Override
    public boolean reapExpired(final JobId id, final LeaseToken expectedToken) {
        final var params = new MapSqlParameterSource()
                .addValue("id", id.value())
                .addValue("token", expectedToken.value());
        return jdbc.update(REAP_EXPIRED_SQL, params) > 0;
    }

    @Override
    public List<Job> findDueScheduled(final Instant now, final QueueName queue, final int limit) {
        final var params = new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("queue", queue.value())
                .addValue("lim", limit);
        return jdbc.query(FIND_DUE_SCHEDULED_SQL, params, RowMappers.JOB);
    }

    @Override
    public boolean promoteScheduled(final JobId id) {
        return jdbc.update(PROMOTE_SCHEDULED_SQL, new MapSqlParameterSource("id", id.value())) > 0;
    }

    @Override
    public long countByStatus(
            final JobStatus status, final Optional<TenantId> tenantId, final Optional<QueueName> queue) {
        final var params = new MapSqlParameterSource()
                .addValue("status", status.name())
                .addValue("tenant", tenantId.map(TenantId::value).orElse(null))
                .addValue("queue", queue.map(QueueName::value).orElse(null));
        final Long n = jdbc.queryForObject(COUNT_BY_STATUS, params, Long.class);
        return n == null ? 0L : n;
    }
}
