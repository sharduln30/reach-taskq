package com.merakilabs.taskq.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Read-side helper for the {@code dlq_reasons} table (write path lives in {@link JdbcJobRepository}). */
@Component
public class JdbcDlqReasonReader {

    private static final String SELECT_BY_IDS =
            """
            SELECT job_id, reason, last_error, final_attempt, dead_at
              FROM dlq_reasons
             WHERE job_id IN (:ids)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDlqReasonReader(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, Row> findByJobIds(final List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        final var params = new MapSqlParameterSource("ids", ids);
        final Map<UUID, Row> out = new HashMap<>();
        jdbc.query(SELECT_BY_IDS, params, (rs, i) -> {
            final UUID id = (UUID) rs.getObject("job_id");
            final String reason = rs.getString("reason");
            final String lastError = rs.getString("last_error");
            final int attempt = rs.getInt("final_attempt");
            final Timestamp ts = rs.getTimestamp("dead_at");
            final Instant deadAt = ts == null ? null : ts.toInstant();
            out.put(id, new Row(id, reason, lastError, attempt, deadAt));
            return null;
        });
        return out;
    }

    public record Row(UUID jobId, String reason, String lastError, int finalAttempt, Instant deadAt) {}
}
