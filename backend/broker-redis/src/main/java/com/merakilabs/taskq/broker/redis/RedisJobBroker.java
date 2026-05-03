package com.merakilabs.taskq.broker.redis;

import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.JobBroker;
import com.merakilabs.taskq.core.port.JobRepository;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis Streams implementation of {@link JobBroker}.
 *
 * <h3>Topology</h3>
 * <ul>
 *   <li>Per-queue stream: {@code taskq:stream:{queue}} (created lazily via {@code XADD MKSTREAM}).</li>
 *   <li>Single global consumer group: {@code workers} (created with {@code XGROUP CREATE MKSTREAM}).</li>
 *   <li>Workers pull with {@code XREADGROUP ... NOACK}, durability lives in Postgres
 *       ({@code jobs.status='LEASED'} + lease reaper). Stream entries are not retained in the PEL.</li>
 * </ul>
 *
 * <h3>Lease ownership</h3>
 * Postgres remains the source of truth for lease state. Pulling from Redis is paired with an
 * atomic {@link JobRepository#lease} update; if the lease is contended (already LEASED in
 * Postgres) we skip emission. Renew / release / retry / dead all mutate the Postgres row,
 * which the reaper inspects on the {@code lease-reaper-interval-seconds} cadence to recover
 * crashed workers.
 *
 * <h3>Schedule</h3>
 * Scheduled jobs are persisted with {@code status='SCHEDULED'} in Postgres. The
 * {@code ScheduledJobPromoter} ticks every {@code scheduler.poll-interval-ms}, transitions due rows
 * to READY, and enqueues a fresh {@code PUBLISH_READY} outbox event. The Redis broker therefore
 * does not need a separate sorted-set delay queue.
 */
public class RedisJobBroker implements JobBroker {

    private static final Logger LOG = LoggerFactory.getLogger(RedisJobBroker.class);

    public static final String GROUP = "workers";
    public static final String STREAM_PREFIX = "taskq:stream:";

    private static final String FIELD_JOB_ID = "jobId";
    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_QUEUE = "queue";

    private final StatefulRedisConnection<String, String> connection;
    private final JobRepository jobs;
    private final Duration leaseTtl;

    public RedisJobBroker(
            final StatefulRedisConnection<String, String> connection,
            final JobRepository jobs,
            final Duration leaseTtl) {
        this.connection = connection;
        this.jobs = jobs;
        this.leaseTtl = leaseTtl;
    }

    private RedisCommands<String, String> cmds() {
        return connection.sync();
    }

    private static String stream(final QueueName q) {
        return STREAM_PREFIX + q.value();
    }

    @PostConstruct
    void ensureGroupOnDefault() {
        ensureGroup(QueueName.DEFAULT);
    }

    private void ensureGroup(final QueueName q) {
        try {
            cmds().xgroupCreate(
                    XReadArgs.StreamOffset.from(stream(q), "0"),
                    GROUP,
                    XGroupCreateArgs.Builder.mkstream());
            LOG.info("Created Redis consumer group {} on {}", GROUP, stream(q));
        } catch (final RedisBusyException already) {
            // group exists, fine
        } catch (final RuntimeException re) {
            LOG.warn("Failed to ensure group {} on {}: {}", GROUP, stream(q), re.getMessage());
        }
    }

    @Override
    public void publishReady(final QueueName queue, final JobId jobId, final TenantId tenantId) {
        ensureGroup(queue);
        cmds().xadd(
                stream(queue),
                Map.of(
                        FIELD_JOB_ID, jobId.value().toString(),
                        FIELD_TENANT_ID, tenantId.value().toString(),
                        FIELD_QUEUE, queue.value()));
    }

    @Override
    public void publishReadyBatch(final List<ReadyEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        // Ensure each distinct queue has its consumer group before pipelining.
        final Set<QueueName> distinctQueues = new HashSet<>(entries.size());
        for (final ReadyEntry e : entries) {
            distinctQueues.add(e.queue());
        }
        for (final QueueName q : distinctQueues) {
            ensureGroup(q);
        }
        // Pipeline N XADD via Lettuce async (auto-flush) so we pay one socket flush per batch.
        final RedisAsyncCommands<String, String> async = connection.async();
        final List<RedisFuture<String>> futures = new ArrayList<>(entries.size());
        for (final ReadyEntry e : entries) {
            futures.add(async.xadd(
                    stream(e.queue()),
                    Map.of(
                            FIELD_JOB_ID, e.jobId().value().toString(),
                            FIELD_TENANT_ID, e.tenantId().value().toString(),
                            FIELD_QUEUE, e.queue().value())));
        }
        for (final RedisFuture<String> f : futures) {
            try {
                f.get(2, TimeUnit.SECONDS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOG.warn("publishReadyBatch interrupted with {} pending futures", futures.size());
                return;
            } catch (final Exception ex) {
                LOG.warn("publishReadyBatch element failed: {}", ex.toString());
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Delivery> pull(
            final QueueName queue, final String consumer, final int maxBatch, final Duration blockFor) {
        ensureGroup(queue);
        final List<StreamMessage<String, String>> msgs;
        try {
            final XReadArgs.StreamOffset<String> offset = XReadArgs.StreamOffset.lastConsumed(stream(queue));
            msgs = cmds().xreadgroup(
                    Consumer.from(GROUP, consumer),
                    XReadArgs.Builder.block(blockFor).count(maxBatch).noack(true),
                    offset);
        } catch (final RuntimeException re) {
            LOG.warn("XREADGROUP failed on {}: {}", stream(queue), re.toString());
            return List.of();
        }
        if (msgs == null || msgs.isEmpty()) {
            return List.of();
        }
        final List<Delivery> out = new ArrayList<>(msgs.size());
        final Instant leasedUntil = Instant.now().plus(leaseTtl);
        for (final StreamMessage<String, String> m : msgs) {
            final Map<String, String> body = m.getBody();
            final String jobIdRaw = body == null ? null : body.get(FIELD_JOB_ID);
            final String tenantRaw = body == null ? null : body.get(FIELD_TENANT_ID);
            if (jobIdRaw == null || tenantRaw == null) {
                continue;
            }
            try {
                final JobId jobId = new JobId(UUID.fromString(jobIdRaw));
                final TenantId tenantId = new TenantId(UUID.fromString(tenantRaw));
                final LeaseToken token = LeaseToken.generate();
                if (jobs.lease(jobId, token, leasedUntil)) {
                    out.add(new Delivery(m.getId(), jobId, tenantId));
                } else {
                    // Already LEASED/SUCCEEDED/DEAD: drop. Reaper republishes via outbox if needed.
                    LOG.debug("Skipping stream entry {}, job {} not in READY", m.getId(), jobId);
                }
            } catch (final IllegalArgumentException bad) {
                LOG.warn("Malformed stream entry {} on {}", m.getId(), stream(queue));
            }
        }
        return out;
    }

    @Override
    public void ack(final QueueName queue, final String consumer, final Delivery delivery) {
        // NOACK consumption, nothing to do. Durability is in Postgres.
    }

    @Override
    public void nack(final QueueName queue, final String consumer, final Delivery delivery) {
        // NOACK + lease expiration handles redelivery. The reaper republishes.
    }

    @Override
    public void schedule(
            final QueueName queue, final JobId jobId, final TenantId tenantId, final Instant runAt) {
        // SCHEDULED jobs live in Postgres; ScheduledJobPromoter promotes them. No-op for Redis.
    }

    @Override
    public Optional<LeaseToken> tryLease(final JobId jobId, final Duration visibility) {
        final LeaseToken token = LeaseToken.generate();
        return jobs.lease(jobId, token, Instant.now().plus(visibility))
                ? Optional.of(token)
                : Optional.empty();
    }

    @Override
    public boolean renewLease(final JobId jobId, final LeaseToken token, final Duration visibility) {
        return jobs.renewLease(jobId, token, Instant.now().plus(visibility));
    }

    @Override
    public void releaseLease(final JobId jobId, final LeaseToken token) {
        // Worker's transition()/scheduleRetry()/markDead() already null out the lease columns.
    }

    @Override
    public int moveDueScheduled(final QueueName queue, final int batchSize) {
        // ScheduledJobPromoter handles SCHEDULED→READY from the jobs table; no Redis delay-queue.
        return 0;
    }

    @Override
    public long readyDepth(final QueueName queue) {
        try {
            return cmds().xlen(stream(queue));
        } catch (final RuntimeException re) {
            LOG.warn("XLEN failed on {}: {}", stream(queue), re.toString());
            return 0L;
        }
    }
}
