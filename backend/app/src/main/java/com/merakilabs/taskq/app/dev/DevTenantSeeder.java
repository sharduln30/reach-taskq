package com.merakilabs.taskq.app.dev;

import com.merakilabs.taskq.api.security.ApiKeyHasher;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.TenantRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a default tenant on startup so the dashboard / Playwright tests have something to talk to.
 * Idempotent, uses {@code findByApiKeyHash} as a presence check.
 */
@Component
@ConditionalOnProperty(prefix = "taskq.dev", name = "seed-tenant", havingValue = "true", matchIfMissing = true)
public class DevTenantSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DevTenantSeeder.class);

    private final TenantRepository tenants;
    private final String seedName;
    private final String seedKey;

    public DevTenantSeeder(
            final TenantRepository tenants,
            @Value("${taskq.dev.seed-tenant-name:demo}") final String seedName,
            @Value("${taskq.dev.seed-tenant-api-key:demo-api-key-do-not-use-in-prod}") final String seedKey) {
        this.tenants = tenants;
        this.seedName = seedName;
        this.seedKey = seedKey;
    }

    @Override
    @Transactional
    public void run(final ApplicationArguments args) {
        final String hash = ApiKeyHasher.hash(seedKey);
        if (tenants.findByApiKeyHash(hash).isPresent()) {
            LOG.info("Dev tenant '{}' already present, skipping seed", seedName);
            return;
        }
        final Tenant t = new Tenant(
                new TenantId(UUID.randomUUID()),
                seedName,
                hash,
                /* rps */ 100,
                /* burst */ 200,
                /* concurrency */ 50,
                /* active */ true,
                Instant.now());
        tenants.insert(t);
        LOG.warn(
                "*** DEV-MODE seeded tenant '{}' with API key '{}'. Use header X-API-Key on all /v1/* requests. ***",
                seedName,
                seedKey);
    }
}
