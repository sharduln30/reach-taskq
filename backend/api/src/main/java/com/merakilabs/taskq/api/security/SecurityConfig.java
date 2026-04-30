package com.merakilabs.taskq.api.security;

import com.merakilabs.taskq.core.port.TenantRepository;
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
    public SecurityFilterChain securityFilterChain(
            final HttpSecurity http, final ApiKeyAuthenticationFilter apiKeyFilter) throws Exception {
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
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
