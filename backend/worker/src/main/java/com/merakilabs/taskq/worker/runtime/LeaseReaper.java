package com.merakilabs.taskq.worker.runtime;

import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.JobEvent;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.port.JobEventLog;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.core.port.Outbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recovers leases held by crashed/stuck workers. Runs every {@code lease-reaper-interval-seconds}.
 *
 * <p>Behaviour:
 * <ol>
 *   <li>find rows where {@code status='LEASED' AND leased_until < now()}</li>
 *   <li>for each, atomically reset to READY (incrementing attempt) using the existing token as a guard</li>
 *   <li>append a REAPED event + re-publish via outbox</li>
 *   <li>if attempts already exhausted, mark DEAD instead of READY (avoid an extra useless retry)</li>
 * </ol>
 *
 * <p>The {@code attempt} increment here is intentional — a crashed worker that didn't ack still
 * counted against the budget. This is the standard at-least-once semantic.
 */
@Component
public class LeaseReaper {

    private static final Logger LOG = LoggerFactory.getLogger(LeaseReaper.class);
    private static final int BATCH = 100;

    private final JobRepository jobs;
    private final Outbox outbox;
    private final JobEventLog events;
    private final Counter deadCounter;
    private final Counter reapedCounter;

    public LeaseReaper(
            final JobRepository jobs,
            final Outbox outbox,
            final JobEventLog events,
            final MeterRegistry meters) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.events = events;
        this.deadCounter = Counter.builder("taskq.jobs.dead")
                .description("Jobs transitioned to DEAD (DLQ) since process start")
                .register(meters);
        this.reapedCounter = Counter.builder("taskq.lease.reaped")
                .description("Lease rows reaped (worker crashed or stalled past lease TTL)")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${taskq.worker.lease-reaper-interval-seconds:10}000")
    @Transactional
    public void reapOnce() {
        final Instant now = Instant.now();
        final var expired = jobs.findExpiredLeases(now, BATCH);
        if (expired.isEmpty()) {
            return;
        }
        LOG.info("LeaseReaper found {} expired lease(s)", expired.size());
        for (final Job j : expired) {
            final LeaseToken token = j.leaseTokenOpt().orElse(null);
            if (token == null) {
                continue;
            }
            final int nextAttempt = j.attempt() + 1;
            if (nextAttempt >= j.maxAttempts()) {
                jobs.markDead(j.id(), nextAttempt, "lease expired and attempts exhausted");
                deadCounter.increment();
                events.append(new JobEvent(
                        UUID.randomUUID(),
                        j.id(),
                        JobEvent.Type.DEAD,
                        nextAttempt,
                        "lease expired and attempts exhausted",
                        null,
                        Instant.now()));
                continue;
            }
            final boolean reaped = jobs.reapExpired(j.id(), token);
            if (reaped) {
                reapedCounter.increment();
                outbox.enqueue(
                        Outbox.EventType.PUBLISH_READY,
                        j.queue(),
                        j.id(),
                        j.tenantId(),
                        Instant.now());
                events.append(new JobEvent(
                        UUID.randomUUID(),
                        j.id(),
                        JobEvent.Type.REAPED,
                        nextAttempt,
                        "lease expired, republished",
                        null,
                        Instant.now()));
            }
        }
    }
}
