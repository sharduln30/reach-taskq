package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.domain.TenantId;
import java.util.Optional;

public interface TenantRepository {

    Tenant insert(Tenant tenant);

    Optional<Tenant> findById(TenantId id);

    Optional<Tenant> findByApiKeyHash(String apiKeyHash);

    /**
     * Persist updated quota / status fields on an existing tenant. Returns the updated row.
     * Throws if no tenant exists with the given id.
     */
    Tenant update(Tenant tenant);
}
