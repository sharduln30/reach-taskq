package com.merakilabs.taskq.worker.runtime;

import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.port.JobBroker;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.core.port.Outbox;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotes due {@code SCHEDULED} jobs to {@code READY} and publishes an outbox row so the broker
 * picks them up. Today we promote the {@code default} queue only; multi-queue is a config knob.
 */
@Component
public class ScheduledJobPromoter {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledJobPromoter.class);
    private static final int BATCH = 200;

    private final JobRepository jobs;
    private final Outbox outbox;

    public ScheduledJobPromoter(final JobRepository jobs, final Outbox outbox) {
        this.jobs = jobs;
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${taskq.scheduler.poll-interval-ms:500}")
    @Transactional
    public void tickOnce() {
        final List<Job> due = jobs.findDueScheduled(Instant.now(), QueueName.DEFAULT, BATCH);
        if (due.isEmpty()) {
            return;
        }
        for (final Job j : due) {
            if (jobs.promoteScheduled(j.id())) {
                outbox.enqueue(
                        Outbox.EventType.PUBLISH_READY,
                        j.queue(),
                        j.id(),
                        j.tenantId(),
                        Instant.now());
            }
        }
        LOG.debug("ScheduledJobPromoter promoted {} job(s)", due.size());
    }
}
