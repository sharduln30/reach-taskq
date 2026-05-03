package com.merakilabs.taskq.api.security;

import com.merakilabs.taskq.core.port.RateLimiter;
import com.merakilabs.taskq.core.port.TenantRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(final TenantRepository tenants) {
        return new ApiKeyAuthenticationFilter(tenants);
    }

    @Bean
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            final HttpSecurity http,
            final ApiKeyAuthenticationFilter apiKeyFilter,
            final RequestIdFilter requestIdFilter,
            final ObjectProvider<RateLimiter> rateLimiterProvider,
            @Value("${taskq.ratelimit.redis-timeout-ms:50}") final long timeoutMs,
            @Value("${taskq.ratelimit.fail-open:true}") final boolean failOpen)
            throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(
                            "/actuator/health/**",
                            "/actuator/info",
                            "/actuator/prometheus",
                            "/v1/info",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            "/ws/**")
                    .permitAll()
                    .requestMatchers("/v1/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .anonymous(a -> a.disable())
            .exceptionHandling(eh -> eh
                    .authenticationEntryPoint((req, res, ex) -> {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setHeader("WWW-Authenticate", "ApiKey realm=\"reach-taskq\"");
                    })
                    .accessDeniedHandler((req, res, ex) -> {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setHeader("WWW-Authenticate", "ApiKey realm=\"reach-taskq\"");
                    }))
            .addFilterBefore(requestIdFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);

        final RateLimiter rl = rateLimiterProvider.getIfAvailable();
        if (rl != null) {
            http.addFilterBefore(
                    new TenantRateLimitFilter(rl, timeoutMs, failOpen),
                    UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }
}
