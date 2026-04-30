package com.merakilabs.taskq.worker.runtime;

import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.JobEvent;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.RetryPolicy;
import com.merakilabs.taskq.core.port.HandlerOutcome;
import com.merakilabs.taskq.core.port.JobBroker;
import com.merakilabs.taskq.core.port.JobEventLog;
import com.merakilabs.taskq.core.port.JobHandler;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.worker.config.TaskqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final String consumerId = CONSUMER_PREFIX + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService leaseLoop =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("worker-lease-loop").factory());
    private final ExecutorService handlerExec =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("worker-job-", 0).factory());

    public WorkerRuntime(
            final JobBroker broker,
            final JobRepository jobs,
            final JobEventLog events,
            final JobHandlerRegistry handlers,
            final TaskqProperties props) {
        this.broker = broker;
        this.jobs = jobs;
        this.events = events;
        this.handlers = handlers;
        this.props = props;
        this.retryPolicy = new RetryPolicy(props.retry().backoffBaseMs(), props.retry().backoffMaxMs());
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
        appendEvent(job.id(), JobEvent.Type.LEASED, job.attempt(), null);

        final JobHandler handler = handlers.resolve(job.type()).orElse(null);
        if (handler == null) {
            recordFailure(job, "No handler registered for type=" + job.type(), /* retryable */ false);
            return;
        }

        try {
            final HandlerOutcome outcome = handler.handle(job);
            applyOutcome(job, outcome);
        } catch (final Throwable t) {
            LOG.warn("Handler threw for job={}", job.id(), t);
            recordFailure(job, t.getClass().getSimpleName() + ": " + t.getMessage(), /* retryable */ true);
        }
    }

    private void applyOutcome(final Job job, final HandlerOutcome outcome) {
        switch (outcome) {
            case HandlerOutcome.Success ignored -> succeed(job);
            case HandlerOutcome.Retry r -> recordFailure(job, r.reason(), /* retryable */ true);
            case HandlerOutcome.Fail f -> recordFailure(job, f.reason(), /* retryable */ false);
        }
    }

    private void succeed(final Job job) {
        final boolean ok = jobs.transition(job.id(), JobStatus.LEASED, JobStatus.SUCCEEDED, null);
        if (ok) {
            appendEvent(job.id(), JobEvent.Type.SUCCEEDED, job.attempt(), null);
        }
    }

    private void recordFailure(final Job job, final String reason, final boolean retryable) {
        final int nextAttempt = job.attempt() + 1;
        final boolean exhausted = nextAttempt >= job.maxAttempts();

        if (!retryable || exhausted) {
            jobs.markDead(job.id(), reason);
            appendEvent(job.id(), JobEvent.Type.DEAD, nextAttempt, reason);
            return;
        }

        final Instant runAt = retryPolicy.nextRunAt(nextAttempt - 1, Instant.now());
        jobs.scheduleRetry(job.id(), runAt, nextAttempt, reason);
        appendEvent(
                job.id(),
                JobEvent.Type.RETRY_SCHEDULED,
                nextAttempt,
                reason + " runAt=" + runAt);
    }

    public boolean tryHeartbeat(final JobId id, final LeaseToken token) {
        final Instant newUntil = Instant.now().plusSeconds(props.worker().leaseTtlSeconds());
        final boolean ok = jobs.renewLease(id, token, newUntil);
        if (ok) {
            appendEvent(id, JobEvent.Type.HEARTBEAT, 0, null);
        }
        return ok;
    }

    private void appendEvent(final JobId jobId, final JobEvent.Type type, final int attempt, final String details) {
        events.append(new JobEvent(
                UUID.randomUUID(), jobId, type, attempt, details, /* traceId */ null, Instant.now()));
    }

    private void sleepQuietly(final Duration d) {
        try {
            Thread.sleep(d);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
