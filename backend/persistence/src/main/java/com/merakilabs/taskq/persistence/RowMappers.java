package com.merakilabs.taskq.persistence;

import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.domain.Priority;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.domain.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.RowMapper;

public final class RowMappers {

    public static final RowMapper<Tenant> TENANT = (rs, rowNum) -> new Tenant(
            new TenantId(rs.getObject("id", java.util.UUID.class)),
            rs.getString("name"),
            rs.getString("api_key_hash"),
            rs.getInt("rate_limit_rps"),
            rs.getInt("rate_limit_burst"),
            rs.getInt("max_concurrency"),
            rs.getBoolean("active"),
            instant(rs, "created_at"));

    public static final RowMapper<Job> JOB = (rs, rowNum) -> new Job(
            new JobId(rs.getObject("id", java.util.UUID.class)),
            new TenantId(rs.getObject("tenant_id", java.util.UUID.class)),
            new QueueName(rs.getString("queue")),
            rs.getString("type"),
            rs.getBytes("payload"),
            JobStatus.valueOf(rs.getString("status")),
            new Priority(rs.getInt("priority")),
            rs.getInt("attempt"),
            rs.getInt("max_attempts"),
            instant(rs, "scheduled_at"),
            instantOrNull(rs, "leased_until"),
            mapLeaseToken(rs.getString("lease_token")),
            rs.getString("idempotency_key"),
            rs.getString("last_error"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private RowMappers() {}

    private static Instant instant(final ResultSet rs, final String col) throws SQLException {
        final Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static Instant instantOrNull(final ResultSet rs, final String col) throws SQLException {
        final Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static LeaseToken mapLeaseToken(final String s) {
        return s == null ? null : new LeaseToken(s);
    }
}
