package com.merakilabs.taskq.worker.replay;

import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.JobEvent;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.error.JobNotFoundException;
import com.merakilabs.taskq.core.port.JobEventLog;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.core.port.Outbox;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DlqReplayService {

    private final JobRepository jobs;
    private final Outbox outbox;
    private final JobEventLog events;
    private final NamedParameterJdbcTemplate jdbc;

    public DlqReplayService(
            final JobRepository jobs,
            final Outbox outbox,
            final JobEventLog events,
            final NamedParameterJdbcTemplate jdbc) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.events = events;
        this.jdbc = jdbc;
    }

    @Transactional
    public Job replay(final JobId id) {
        final Job job = jobs.findById(id).orElseThrow(() -> new JobNotFoundException(id));
        if (job.status() != JobStatus.DEAD) {
            throw new IllegalStateException("Only DEAD jobs can be replayed; current=" + job.status());
        }

        final Instant now = Instant.now();
        jdbc.update(
                """
                UPDATE jobs
                   SET status = 'READY'::job_status,
                       attempt = 0,
                       last_error = NULL,
                       leased_until = NULL,
                       lease_token = NULL,
                       scheduled_at = :now,
                       updated_at = now()
                 WHERE id = :id AND status = 'DEAD'::job_status
                """,
                new MapSqlParameterSource()
                        .addValue("id", id.value())
                        .addValue("now", java.sql.Timestamp.from(now)));

        outbox.enqueue(
                Outbox.EventType.PUBLISH_READY,
                job.queue(),
                job.id(),
                job.tenantId(),
                now);

        events.append(new JobEvent(
                UUID.randomUUID(),
                job.id(),
                JobEvent.Type.REPLAYED,
                0,
                "DLQ replay",
                null,
                now));

        return jobs.findById(id).orElseThrow();
    }
}
