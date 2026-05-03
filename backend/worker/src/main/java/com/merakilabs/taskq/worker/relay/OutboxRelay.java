package com.merakilabs.taskq.worker.relay;

import com.merakilabs.taskq.core.port.JobBroker;
import com.merakilabs.taskq.core.port.Outbox;
import com.merakilabs.taskq.worker.config.TaskqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the outbox table on a virtual thread, publishes batches to the broker, and marks rows
 * published. Multiple instances of the app can run this in parallel — each batch is selected
 * via {@code FOR UPDATE SKIP LOCKED} so they don't conflict.
 */
@Component
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final Outbox outbox;
    private final JobBroker broker;
    private final TransactionTemplate tx;
    private final TaskqProperties props;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("outbox-relay").factory());

    public OutboxRelay(
            final Outbox outbox,
            final JobBroker broker,
            final TransactionTemplate tx,
            final TaskqProperties props) {
        this.outbox = outbox;
        this.broker = broker;
        this.tx = tx;
        this.props = props;
    }

    @PostConstruct
    void start() {
        running.set(true);
        executor.submit(this::loop);
        LOG.info(
                "OutboxRelay started, pollInterval={}ms batchSize={}",
                props.outbox().pollIntervalMs(),
                props.outbox().batchSize());
    }

    @PreDestroy
    void stop() {
        running.set(false);
        executor.shutdown();
    }

    private void loop() {
        while (running.get()) {
            try {
                final int published = pollOnce();
                if (published == 0) {
                    Thread.sleep(props.outbox().pollIntervalMs());
                }
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (final RuntimeException re) {
                LOG.warn("OutboxRelay iteration failed", re);
                sleepBackoff();
            }
        }
    }

    /** Visible-for-testing: drives one iteration synchronously. */
    public int pollOnce() {
        return tx.<Integer>execute(status -> {
            final List<Outbox.Pending> batch = outbox.pollUnpublished(props.outbox().batchSize());
            if (batch.isEmpty()) {
                return 0;
            }
            // Bucket PUBLISH_READY into one pipelined batch (one Redis flush);
            // SCHEDULE entries are in-memory at the broker (no remote call) and
            // can stay in a per-element loop without a perf hit.
            final List<JobBroker.ReadyEntry> ready = new ArrayList<>(batch.size());
            for (final Outbox.Pending p : batch) {
                if (p.type() == Outbox.EventType.PUBLISH_READY) {
                    ready.add(new JobBroker.ReadyEntry(p.queue(), p.jobId(), p.tenantId()));
                } else {
                    broker.schedule(p.queue(), p.jobId(), p.tenantId(), p.runAt());
                }
            }
            if (!ready.isEmpty()) {
                broker.publishReadyBatch(ready);
            }
            final List<UUID> ids = batch.stream().map(Outbox.Pending::id).collect(Collectors.toList());
            outbox.markPublished(ids);
            return batch.size();
        });
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(Duration.ofSeconds(1).toMillis());
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
