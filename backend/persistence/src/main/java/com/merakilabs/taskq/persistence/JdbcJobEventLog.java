package com.merakilabs.taskq.persistence;

import com.merakilabs.taskq.core.domain.JobEvent;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.port.JobEventLog;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcJobEventLog implements JobEventLog {

    private static final String INSERT_SQL =
            """
            INSERT INTO job_events (id, job_id, type, attempt, details, trace_id, occurred_at)
            VALUES (:id, :job_id, CAST(:type AS job_event_type), :attempt, :details, :trace_id, :occurred_at)
            """;

    private static final String FIND_SQL =
            """
            SELECT id, job_id, type, attempt, details, trace_id, occurred_at
              FROM job_events
             WHERE job_id = :job_id
             ORDER BY occurred_at DESC
             LIMIT :lim
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcJobEventLog(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void append(final JobEvent event) {
        final var params = new MapSqlParameterSource()
                .addValue("id", event.id())
                .addValue("job_id", event.jobId().value())
                .addValue("type", event.type().name())
                .addValue("attempt", event.attempt())
                .addValue("details", event.details())
                .addValue("trace_id", event.traceId())
                .addValue("occurred_at", Timestamp.from(event.occurredAt()));
        jdbc.update(INSERT_SQL, params);
    }

    @Override
    public List<JobEvent> findByJobId(final JobId jobId, final int limit) {
        return jdbc.query(
                FIND_SQL,
                new MapSqlParameterSource()
                        .addValue("job_id", jobId.value())
                        .addValue("lim", limit),
                (rs, rowNum) -> new JobEvent(
                        rs.getObject("id", UUID.class),
                        new JobId(rs.getObject("job_id", UUID.class)),
                        JobEvent.Type.valueOf(rs.getString("type")),
                        rs.getInt("attempt"),
                        rs.getString("details"),
                        rs.getString("trace_id"),
                        rs.getTimestamp("occurred_at").toInstant()));
    }
}
