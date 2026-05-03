package com.merakilabs.taskq.worker.submission;

import com.merakilabs.taskq.core.domain.IdempotencyHasher;
import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.JobEvent;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.SubmitJobCommand;
import com.merakilabs.taskq.core.domain.SubmitResult;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.port.IdempotencyStore;
import com.merakilabs.taskq.core.port.JobEventLog;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.core.port.JobStatusBroadcaster;
import com.merakilabs.taskq.core.port.Outbox;
import com.merakilabs.taskq.core.port.TenantRepository;
import com.merakilabs.taskq.worker.config.TaskqProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-tx submission service. Orchestrates:
 * <ol>
 *   <li>idempotency lookup (short-circuit replay → return existing job)</li>
 *   <li>tenant active + payload-size guards</li>
 *   <li>insert {@code jobs} row (READY or SCHEDULED based on runAt)</li>
 *   <li>insert {@code idempotency_keys} row when caller supplied one</li>
 *   <li>insert {@code outbox} row so the relay publishes to the broker</li>
 *   <li>append {@code job_events.SUBMITTED}</li>
 * </ol>
 *
 * All inside one DB transaction → no dual-write inconsistency.
 */
@Service
public class JobSubmissionService {

    private static final Logger LOG = LoggerFactory.getLogger(JobSubmissionService.class);

    private final TenantRepository tenants;
    private final JobRepository jobs;
    private final IdempotencyStore idempotency;
    private final Outbox outbox;
    private final JobEventLog events;
    private final NamedParameterJdbcTemplate jdbc;
    private final TaskqProperties props;
    private final JobStatusBroadcaster broadcaster;

    public JobSubmissionService(
            final TenantRepository tenants,
            final JobRepository jobs,
            final IdempotencyStore idempotency,
            final Outbox outbox,
            final JobEventLog events,
            final NamedParameterJdbcTemplate jdbc,
            final TaskqProperties props,
            final ObjectProvider<JobStatusBroadcaster> broadcasterProvider) {
        this.tenants = tenants;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.events = events;
        this.jdbc = jdbc;
        this.props = props;
        this.broadcaster = broadcasterProvider.getIfAvailable(() -> JobStatusBroadcaster.NOOP);
    }

    /**
     * Public entry point. Wraps {@link #submitInTx} so that a unique-violation race on
     * {@code idempotency_keys (tenant_id, key)} (two concurrent submits with the same key) is
     * caught and converted into an idempotent replay rather than bubbling out as HTTP 500.
     */
    public SubmitResult submit(final SubmitJobCommand cmd) {
        try {
            return submitInTx(cmd);
        } catch (final DataIntegrityViolationException race) {
            if (cmd.idempotencyKey().isEmpty()) {
                throw race;
            }
            final String requestHash = IdempotencyHasher.hash(cmd);
            final Optional<IdempotencyStore.Lookup> hit =
                    idempotency.find(cmd.tenantId(), cmd.idempotencyKey().get(), requestHash);
            if (hit.isEmpty()) {
                throw race;
            }
            if (hit.get().hashMismatch()) {
                return new SubmitResult.IdempotencyConflict(
                        hit.get().jobId(),
                        "Idempotency-Key reused with different payload (hash mismatch).");
            }
            return jobs.findById(hit.get().jobId())
                    .<SubmitResult>map(SubmitResult.IdempotentReplay::new)
                    .orElseThrow(() -> race);
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SubmitResult submitInTx(final SubmitJobCommand cmd) {
        final Tenant tenant = tenants
                .findById(cmd.tenantId())
                .orElseThrow(() -> new IllegalStateException("Unknown tenant: " + cmd.tenantId()));
        if (!tenant.active()) {
            return new SubmitResult.TenantInactive();
        }
        if (cmd.payload().length > props.payload().maxBytes()) {
            return new SubmitResult.PayloadTooLarge(props.payload().maxBytes());
        }

        final String requestHash = IdempotencyHasher.hash(cmd);

        if (cmd.idempotencyKey().isPresent()) {
            final String key = cmd.idempotencyKey().get();
            final Optional<IdempotencyStore.Lookup> existing = idempotency.find(cmd.tenantId(), key, requestHash);
            if (existing.isPresent()) {
                final var hit = existing.get();
                if (hit.hashMismatch()) {
                    return new SubmitResult.IdempotencyConflict(
                            hit.jobId(),
                            "Idempotency-Key reused with different payload (hash mismatch).");
                }
                return jobs.findById(hit.jobId())
                        .<SubmitResult>map(SubmitResult.IdempotentReplay::new)
                        .orElseGet(() -> new SubmitResult.IdempotencyConflict(
                                hit.jobId(),
                                "Idempotency-Key references a missing job (cleanup race)."));
            }
        }

        final Instant now = Instant.now();
        final Instant runAt = cmd.runAt().orElse(now);
        final JobStatus status = runAt.isAfter(now) ? JobStatus.SCHEDULED : JobStatus.READY;

        final Job job = new Job(
                JobId.random(),
                cmd.tenantId(),
                cmd.queue(),
                cmd.type(),
                cmd.payload(),
                status,
                cmd.priority(),
                /* attempt */ 0,
                cmd.maxAttempts(),
                runAt,
                /* leasedUntil */ null,
                /* leaseToken */ null,
                cmd.idempotencyKey().orElse(null),
                /* lastError */ null,
                now,
                now);
        jobs.insert(job);

        if (cmd.idempotencyKey().isPresent()) {
            insertIdempotencyRow(job, requestHash, now);
        }

        outbox.enqueue(
                status == JobStatus.SCHEDULED ? Outbox.EventType.SCHEDULE : Outbox.EventType.PUBLISH_READY,
                cmd.queue(),
                job.id(),
                cmd.tenantId(),
                runAt);

        events.append(new JobEvent(
                UUID.randomUUID(),
                job.id(),
                JobEvent.Type.SUBMITTED,
                0,
                "queue=" + cmd.queue() + " type=" + cmd.type() + " runAt=" + runAt,
                /* traceId */ null,
                now));

        LOG.info(
                "Submitted job {} tenant={} queue={} type={} status={}",
                job.id(),
                cmd.tenantId(),
                cmd.queue(),
                cmd.type(),
                status);
        broadcaster.publish(job.id(), cmd.tenantId(), cmd.queue(), status, 0);
        return new SubmitResult.Created(job);
    }

    private void insertIdempotencyRow(final Job job, final String requestHash, final Instant now) {
        final Instant expiresAt = now.plus(Duration.ofHours(props.idempotency().ttlHours()));
        final int rows = jdbc.update(
                """
                INSERT INTO idempotency_keys (tenant_id, key, job_id, request_hash, created_at, expires_at)
                VALUES (:tenant, :key, :job, :hash, :now, :expires)
                ON CONFLICT (tenant_id, key) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("tenant", job.tenantId().value())
                        .addValue("key", job.idempotencyKey())
                        .addValue("job", job.id().value())
                        .addValue("hash", requestHash)
                        .addValue("now", Timestamp.from(now))
                        .addValue("expires", Timestamp.from(expiresAt)));
        if (rows == 0) {
            // Lost the race. Force a unique-violation so submit() re-resolves the committed row
            // (replay vs conflict).
            throw new DuplicateKeyException(
                    "idempotency_keys: duplicate (tenant_id,key), concurrent submit raced");
        }
    }
}
