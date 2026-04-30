package com.merakilabs.taskq.api.security;

import com.merakilabs.taskq.core.domain.Tenant;
import java.util.Collection;
import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public final class TenantAuthentication extends AbstractAuthenticationToken {

    private static final Collection<? extends GrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_TENANT"));

    private final transient Tenant tenant;

    public TenantAuthentication(final Tenant tenant) {
        super(AUTHORITIES);
        this.tenant = tenant;
        setAuthenticated(true);
    }

    public Tenant tenant() {
        return tenant;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return tenant.id().value();
    }

    @Override
    public String getName() {
        return tenant.name();
    }
}
