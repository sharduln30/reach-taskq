package com.merakilabs.taskq.persistence;

import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.IdempotencyStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String SELECT_SQL =
            """
            SELECT job_id, request_hash, expires_at
              FROM idempotency_keys
             WHERE tenant_id = :tenant AND key = :key AND expires_at > now()
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIdempotencyStore(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Lookup> find(final TenantId tenantId, final String key, final String requestHash) {
        try {
            final var found = jdbc.queryForObject(
                    SELECT_SQL,
                    new MapSqlParameterSource()
                            .addValue("tenant", tenantId.value())
                            .addValue("key", key),
                    (rs, rowNum) -> new Lookup(
                            new JobId(rs.getObject("job_id", UUID.class)),
                            rs.getString("request_hash"),
                            toInstant(rs.getTimestamp("expires_at")),
                            !rs.getString("request_hash").equals(requestHash)));
            return Optional.of(found);
        } catch (final EmptyResultDataAccessException notFound) {
            return Optional.empty();
        }
    }

    private static Instant toInstant(final java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
