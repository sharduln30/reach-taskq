package com.merakilabs.taskq.api.tenants;

import com.merakilabs.taskq.api.security.ApiKeyAuthenticationFilter;
import com.merakilabs.taskq.api.security.TenantContext;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.port.TenantRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/v1/tenants", produces = MediaType.APPLICATION_JSON_VALUE)
public class TenantsController {

    private final TenantRepository tenants;
    private final ApiKeyAuthenticationFilter apiKeyFilter;

    public TenantsController(final TenantRepository tenants, final ApiKeyAuthenticationFilter apiKeyFilter) {
        this.tenants = tenants;
        this.apiKeyFilter = apiKeyFilter;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return toResponse(TenantContext.currentTenant());
    }

    /**
     * Update editable fields on the current tenant. Quotas + active flag may be tuned;
     * id, api_key_hash and created_at are immutable. Cache TTL on the API-key filter is
     * 30s, so propagation to other instances is eventually-consistent.
     */
    @PatchMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> updateMe(@Valid @RequestBody final UpdateTenantRequest body) {
        final Tenant current = TenantContext.currentTenant();
        final String name = body.name() == null || body.name().isBlank() ? current.name() : body.name().trim();
        final int rps = body.rateLimitRps() == null ? current.rateLimitRps() : body.rateLimitRps();
        final int burst = body.rateLimitBurst() == null ? current.rateLimitBurst() : body.rateLimitBurst();
        final int concurrency = body.maxConcurrency() == null ? current.maxConcurrency() : body.maxConcurrency();
        final boolean active = body.active() == null ? current.active() : body.active();
        if (burst < rps) {
            return ResponseEntity.unprocessableEntity().body(Map.of(
                    "error", "validation_failed",
                    "message", "rate_limit_burst must be >= rate_limit_rps"));
        }
        final Tenant updated = new Tenant(
                current.id(),
                name,
                current.apiKeyHash(),
                rps,
                burst,
                concurrency,
                active,
                current.createdAt());
        tenants.update(updated);
        apiKeyFilter.invalidateAll();
        return ResponseEntity.ok(toResponse(updated));
    }

    private static Map<String, Object> toResponse(final Tenant t) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id().value());
        m.put("name", t.name());
        m.put("active", t.active());
        m.put("rate_limit_rps", t.rateLimitRps());
        m.put("rate_limit_burst", t.rateLimitBurst());
        m.put("max_concurrency", t.maxConcurrency());
        m.put("created_at", t.createdAt());
        return m;
    }

    public record UpdateTenantRequest(
            @Size(max = 120) String name,
            @Min(1) Integer rateLimitRps,
            @Min(1) Integer rateLimitBurst,
            @Min(1) Integer maxConcurrency,
            Boolean active) {}
}
