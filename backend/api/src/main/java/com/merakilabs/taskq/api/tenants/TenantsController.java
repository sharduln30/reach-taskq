package com.merakilabs.taskq.api.tenants;

import com.merakilabs.taskq.api.security.TenantContext;
import com.merakilabs.taskq.core.domain.Tenant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/v1/tenants", produces = MediaType.APPLICATION_JSON_VALUE)
public class TenantsController {

    @GetMapping("/me")
    public Map<String, Object> me() {
        final Tenant t = TenantContext.currentTenant();
        return Map.of(
                "id", t.id().value(),
                "name", t.name(),
                "active", t.active(),
                "rate_limit_rps", t.rateLimitRps(),
                "rate_limit_burst", t.rateLimitBurst(),
                "max_concurrency", t.maxConcurrency(),
                "created_at", t.createdAt());
    }
}
