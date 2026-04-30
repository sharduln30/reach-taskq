package com.merakilabs.taskq.api.ws;

import com.merakilabs.taskq.core.port.TenantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TenantRepository tenants;
    private final JobEventBroadcaster broadcaster;

    public WebSocketConfig(final TenantRepository tenants, final JobEventBroadcaster broadcaster) {
        this.tenants = tenants;
        this.broadcaster = broadcaster;
    }

    @Bean
    public JobEventsHandler jobEventsHandler() {
        return new JobEventsHandler(tenants, broadcaster);
    }

    @Override
    public void registerWebSocketHandlers(final WebSocketHandlerRegistry registry) {
        registry.addHandler(jobEventsHandler(), "/ws/jobs").setAllowedOriginPatterns("*");
    }
}
