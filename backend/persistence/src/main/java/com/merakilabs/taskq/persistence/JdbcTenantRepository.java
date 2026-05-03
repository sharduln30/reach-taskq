package com.merakilabs.taskq.persistence;

import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.TenantRepository;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenantRepository implements TenantRepository {

    private static final String INSERT_SQL =
            """
            INSERT INTO tenants
              (id, name, api_key_hash, rate_limit_rps, rate_limit_burst,
               max_concurrency, active, created_at)
            VALUES
              (:id, :name, :api_key_hash, :rps, :burst, :concurrency, :active, :created_at)
            """;

    private static final String SELECT_BY_ID =
            "SELECT * FROM tenants WHERE id = :id";

    private static final String SELECT_BY_API_KEY =
            "SELECT * FROM tenants WHERE api_key_hash = :hash";

    private static final String UPDATE_SQL =
            """
            UPDATE tenants
               SET name = :name,
                   rate_limit_rps = :rps,
                   rate_limit_burst = :burst,
                   max_concurrency = :concurrency,
                   active = :active
             WHERE id = :id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTenantRepository(final NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Tenant insert(final Tenant tenant) {
        final var params = new MapSqlParameterSource()
                .addValue("id", tenant.id().value())
                .addValue("name", tenant.name())
                .addValue("api_key_hash", tenant.apiKeyHash())
                .addValue("rps", tenant.rateLimitRps())
                .addValue("burst", tenant.rateLimitBurst())
                .addValue("concurrency", tenant.maxConcurrency())
                .addValue("active", tenant.active())
                .addValue("created_at", Timestamp.from(tenant.createdAt()));
        jdbc.update(INSERT_SQL, params);
        return tenant;
    }

    @Override
    public Optional<Tenant> findById(final TenantId id) {
        return queryOne(SELECT_BY_ID, new MapSqlParameterSource("id", id.value()));
    }

    @Override
    public Optional<Tenant> findByApiKeyHash(final String apiKeyHash) {
        return queryOne(SELECT_BY_API_KEY, new MapSqlParameterSource("hash", apiKeyHash));
    }

    @Override
    public Tenant update(final Tenant tenant) {
        final var params = new MapSqlParameterSource()
                .addValue("id", tenant.id().value())
                .addValue("name", tenant.name())
                .addValue("rps", tenant.rateLimitRps())
                .addValue("burst", tenant.rateLimitBurst())
                .addValue("concurrency", tenant.maxConcurrency())
                .addValue("active", tenant.active());
        final int n = jdbc.update(UPDATE_SQL, params);
        if (n == 0) {
            throw new IllegalStateException("Tenant not found: " + tenant.id().value());
        }
        return tenant;
    }

    private Optional<Tenant> queryOne(final String sql, final MapSqlParameterSource params) {
        try {
            return Optional.of(jdbc.queryForObject(sql, params, RowMappers.TENANT));
        } catch (final EmptyResultDataAccessException notFound) {
            return Optional.empty();
        }
    }
}
