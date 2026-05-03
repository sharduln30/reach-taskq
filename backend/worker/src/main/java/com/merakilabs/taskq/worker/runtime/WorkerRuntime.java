package com.merakilabs.taskq.worker.runtime;

import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.JobEvent;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.RetryPolicy;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.ConcurrencyLimiter;
import com.merakilabs.taskq.core.port.HandlerOutcome;
import com.merakilabs.taskq.core.port.JobBroker;
import com.merakilabs.taskq.core.port.JobEventLog;
import com.merakilabs.taskq.core.port.JobHandler;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.core.port.JobStatusBroadcaster;
import com.merakilabs.taskq.core.port.Outbox;
import com.merakilabs.taskq.worker.config.TaskqProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The worker. Pulls leases from the broker, dispatches to a {@link JobHandler}, and applies the
 * outcome (succeed / retry-with-backoff / dead-letter) atomically against the durable store.
 *
 * <p>Each in-flight job runs on its own virtual thread, which keeps the handler code synchronous.
 * Bounded resources (DB, broker connection) are protected by their respective pools, not by the
 * worker itself.
 */
@Component
@ConditionalOnProperty(name = "taskq.worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerRuntime.class);
    private static final String CONSUMER_PREFIX = "worker-";
    private static final QueueName DEFAULT_QUEUE = QueueName.DEFAULT;
    private static final int PULL_BATCH = 16;

    private final JobBroker broker;
    private final JobRepository jobs;
    private final JobEventLog events;
    private final JobHandlerRegistry handlers;
    private final TaskqProperties props;
    private final RetryPolicy retryPolicy;
    private final TenantCache tenantCache;
    private final Outbox outbox;
    private final ConcurrencyLimiter concurrency;
    private final JobStatusBroadcaster broadcaster;
    private final long yieldDelayMs;
    private final MeterRegistry meters;
    private final Timer leaseAgeTimer;
    private final Counter deadCounter;

    private final String consumerId = CONSUMER_PREFIX + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService leaseLoop =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("worker-lease-loop").factory());
    private final ExecutorService handlerExec =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("worker-job-", 0).factory());
    // Single-threaded platform scheduler for heartbeat ticks. Each tick is a short JDBC + Redis
    // round trip; ScheduledExecutorService needs a fixed-size pool so we don't use virtual threads.
    private final ScheduledExecutorService heartbeatExec =
            Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "worker-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public WorkerRuntime(
            final JobBroker broker,
            final JobRepository jobs,
            final JobEventLog events,
            final JobHandlerRegistry handlers,
            final TaskqProperties props,
            final TenantCache tenantCache,
            final Outbox outbox,
            final ObjectProvider<ConcurrencyLimiter> concurrencyProvider,
            final ObjectProvider<JobStatusBroadcaster> broadcasterProvider,
            final MeterRegistry meters,
            @Value("${taskq.concurrency.yield-delay-ms:100}") final long yieldDelayMs) {
        this.broker = broker;
        this.jobs = jobs;
        this.events = events;
        this.handlers = handlers;
        this.props = props;
        this.retryPolicy = new RetryPolicy(props.retry().backoffBaseMs(), props.retry().backoffMaxMs());
        this.tenantCache = tenantCache;
        this.outbox = outbox;
        this.concurrency = concurrencyProvider.getIfAvailable();
        this.broadcaster = broadcasterProvider.getIfAvailable(() -> JobStatusBroadcaster.NOOP);
        this.yieldDelayMs = yieldDelayMs;
        this.meters = meters;
        this.leaseAgeTimer = Timer.builder("taskq.lease.age")
                .description("Time between job creation and the moment a worker leases it")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(meters);
        this.deadCounter = Counter.builder("taskq.jobs.dead")
                .description("Jobs transitioned to DEAD (DLQ) since process start")
                .register(meters);
    }

    private Timer processingTimerFor(final Job job) {
        return Timer.builder("taskq.processing.duration")
                .description("End-to-end handler invocation latency from LEASED to terminal status")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .tags(Tags.of("queue", job.queue().value(), "type", job.type()))
                .register(meters);
    }

    @PostConstruct
    void start() {
        running.set(true);
        leaseLoop.submit(this::runLeaseLoop);
        LOG.info("WorkerRuntime started consumer={}", consumerId);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        leaseLoop.shutdown();
        handlerExec.shutdown();
        heartbeatExec.shutdown();
    }

    private void runLeaseLoop() {
        while (running.get()) {
            try {
                pullAndDispatchOnce();
            } catch (final RuntimeException re) {
                LOG.warn("Lease loop iteration failed", re);
                sleepQuietly(Duration.ofSeconds(1));
            }
        }
    }

    /** Visible-for-testing: drives one iteration synchronously. */
    public int pullAndDispatchOnce() {
        final List<JobBroker.Delivery> deliveries =
                broker.pull(DEFAULT_QUEUE, consumerId, PULL_BATCH, Duration.ofSeconds(2));
        for (final JobBroker.Delivery d : deliveries) {
            handlerExec.submit(() -> processOne(d));
        }
        return deliveries.size();
    }

    private void processOne(final JobBroker.Delivery delivery) {
        final Job job = jobs.findById(delivery.jobId()).orElse(null);
        if (job == null) {
            LOG.warn("Delivery for missing job, dropping: {}", delivery.jobId());
            return;
        }
        // Buffer events so processOne flushes them in one batched insert instead of 2-3 round trips.
        final List<JobEvent> pending = new ArrayList<>(2);
        pending.add(newEvent(job.id(), JobEvent.Type.LEASED, job.attempt(), null));
        broadcaster.publish(job.id(), job.tenantId(), job.queue(), JobStatus.LEASED, job.attempt());

        leaseAgeTimer.record(Duration.between(job.createdAt(), Instant.now()));
        final Timer.Sample sample = Timer.start(meters);

        // Heartbeat: refresh lease + concurrency-slot TTL at lease-ttl/3 so long-running handlers
        // don't get reaped or pruned. Cancelled in finally.
        final LeaseToken leaseToken = job.leaseTokenOpt().orElse(null);
        ScheduledFuture<?> heartbeat = null;
        if (leaseToken != null) {
            final long periodMs = Math.max(props.worker().leaseTtlSeconds() * 1000L / 3L, 1000L);
            heartbeat = heartbeatExec.scheduleAtFixedRate(
                    () -> {
                        try {
                            tryHeartbeat(job.id(), leaseToken);
                        } catch (final RuntimeException re) {
                            LOG.debug("Heartbeat failed for {} (will retry)", job.id(), re);
                        }
                    },
                    periodMs,
                    periodMs,
                    TimeUnit.MILLISECONDS);
        }

        try {
            final JobHandler handler = handlers.resolve(job.type()).orElse(null);
            if (handler == null) {
                recordFailure(job, "No handler registered for type=" + job.type(), /* retryable */ false, pending);
                return;
            }

            // If the tenant is at its in-flight cap, yield: clear lease, requeue with a small
            // delay. Don't increment attempt; hitting the cap isn't a job failure.
            final String holderToken = leaseToken == null ? null : leaseToken.value();
            final Tenant tenant = tenantCache.find(job.tenantId()).orElse(null);
            if (concurrency != null && tenant != null && holderToken != null) {
                if (!concurrency.tryAcquire(tenant.id(), tenant.maxConcurrency(), holderToken)) {
                    yieldBack(job, tenant.id(), "concurrency cap reached", pending);
                    return;
                }
            }

            try {
                final HandlerOutcome outcome = handler.handle(job);
                applyOutcome(job, outcome, pending);
            } catch (final Throwable t) {
                LOG.warn("Handler threw for job={}", job.id(), t);
                recordFailure(
                        job,
                        t.getClass().getSimpleName() + ": " + t.getMessage(),
                        /* retryable */ true,
                        pending);
            } finally {
                if (concurrency != null && holderToken != null) {
                    concurrency.release(job.tenantId(), holderToken);
                }
            }
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            sample.stop(processingTimerFor(job));
            flushEvents(pending);
        }
    }

    /**
     * Push the job back to the broker without incrementing attempt. The lease row
     * is reset to READY and a fresh outbox PUBLISH_READY is scheduled with a small
     * delay so we don't busy-spin on a saturated tenant.
     */
    private void yieldBack(
            final Job job, final TenantId tenantId, final String reason, final List<JobEvent> pending) {
        final Instant runAt = Instant.now().plusMillis(Math.max(yieldDelayMs, 10));
        final boolean reset = jobs.transition(job.id(), JobStatus.LEASED, JobStatus.READY, null);
        if (!reset) {
            LOG.warn("Yield-back failed to reset job {} (race?), relying on lease reaper", job.id());
            return;
        }
        outbox.enqueue(Outbox.EventType.PUBLISH_READY, job.queue(), job.id(), tenantId, runAt);
        pending.add(newEvent(job.id(), JobEvent.Type.RETRY_SCHEDULED, job.attempt(), "yield: " + reason));
        broadcaster.publish(job.id(), tenantId, job.queue(), JobStatus.READY, job.attempt());
        LOG.debug("Yielded job {} tenant={} reason={}", job.id(), tenantId, reason);
    }

    private void applyOutcome(final Job job, final HandlerOutcome outcome, final List<JobEvent> pending) {
        switch (outcome) {
            case HandlerOutcome.Success ignored -> succeed(job, pending);
            case HandlerOutcome.Retry r -> recordFailure(job, r.reason(), /* retryable */ true, pending);
            case HandlerOutcome.Fail f -> recordFailure(job, f.reason(), /* retryable */ false, pending);
        }
    }

    private void succeed(final Job job, final List<JobEvent> pending) {
        final boolean ok = jobs.transition(job.id(), JobStatus.LEASED, JobStatus.SUCCEEDED, null);
        if (ok) {
            pending.add(newEvent(job.id(), JobEvent.Type.SUCCEEDED, job.attempt(), null));
            broadcaster.publish(job.id(), job.tenantId(), job.queue(), JobStatus.SUCCEEDED, job.attempt());
        }
    }

    private void recordFailure(
            final Job job, final String reason, final boolean retryable, final List<JobEvent> pending) {
        final int nextAttempt = job.attempt() + 1;
        final boolean exhausted = nextAttempt >= job.maxAttempts();

        if (!retryable || exhausted) {
            jobs.markDead(job.id(), nextAttempt, reason);
            pending.add(newEvent(job.id(), JobEvent.Type.DEAD, nextAttempt, reason));
            broadcaster.publish(job.id(), job.tenantId(), job.queue(), JobStatus.DEAD, nextAttempt);
            deadCounter.increment();
            return;
        }

        final Instant runAt = retryPolicy.nextRunAt(nextAttempt - 1, Instant.now());
        jobs.scheduleRetry(job.id(), runAt, nextAttempt, reason);
        pending.add(newEvent(
                job.id(), JobEvent.Type.RETRY_SCHEDULED, nextAttempt, reason + " runAt=" + runAt));
        broadcaster.publish(job.id(), job.tenantId(), job.queue(), JobStatus.SCHEDULED, nextAttempt);
    }

    public boolean tryHeartbeat(final JobId id, final LeaseToken token) {
        final Instant newUntil = Instant.now().plusSeconds(props.worker().leaseTtlSeconds());
        final boolean ok = jobs.renewLease(id, token, newUntil);
        if (ok) {
            appendEvent(id, JobEvent.Type.HEARTBEAT, 0, null);
            // Refresh the concurrency-slot TTL. If refresh returns false the slot was already
            // pruned by Redis; caller should yield on the next tick.
            if (concurrency != null) {
                jobs.findById(id).ifPresent(j -> concurrency.refresh(j.tenantId(), token.value()));
            }
        }
        return ok;
    }

    private void appendEvent(final JobId jobId, final JobEvent.Type type, final int attempt, final String details) {
        events.append(newEvent(jobId, type, attempt, details));
    }

    private static JobEvent newEvent(
            final JobId jobId, final JobEvent.Type type, final int attempt, final String details) {
        return new JobEvent(
                UUID.randomUUID(), jobId, type, attempt, details, /* traceId */ null, Instant.now());
    }

    private void flushEvents(final List<JobEvent> pending) {
        if (pending.isEmpty()) {
            return;
        }
        try {
            events.appendAll(pending);
        } catch (final RuntimeException re) {
            LOG.warn("Failed to flush {} job events (non-fatal)", pending.size(), re);
        }
    }

    private void sleepQuietly(final Duration d) {
        try {
            Thread.sleep(d);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
