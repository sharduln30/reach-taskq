package com.merakilabs.taskq.api.security;

import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.domain.TenantId;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class TenantContext {

    private TenantContext() {}

    public static Tenant currentTenant() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof TenantAuthentication ta) {
            return ta.tenant();
        }
        throw new IllegalStateException("No authenticated tenant on this request");
    }

    public static TenantId currentTenantId() {
        return currentTenant().id();
    }
}
