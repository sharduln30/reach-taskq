package com.merakilabs.taskq.api.ws;

import com.merakilabs.taskq.api.security.ApiKeyHasher;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.port.TenantRepository;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class JobEventsHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(JobEventsHandler.class);

    private final TenantRepository tenants;
    private final JobEventBroadcaster broadcaster;

    public JobEventsHandler(final TenantRepository tenants, final JobEventBroadcaster broadcaster) {
        this.tenants = tenants;
        this.broadcaster = broadcaster;
    }

    @Override
    public void afterConnectionEstablished(final WebSocketSession session) throws IOException {
        final Optional<Tenant> tenant = resolveTenant(session);
        if (tenant.isEmpty() || !tenant.get().active()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid api key"));
            return;
        }
        session.getAttributes().put("tenantId", tenant.get().id().value());
        broadcaster.register(tenant.get().id().value(), session);
        session.sendMessage(new TextMessage(
                "{\"type\":\"hello\",\"tenant\":\"" + tenant.get().id().value() + "\"}"));
        LOG.info("WS connected session={} tenant={}", session.getId(), tenant.get().id().value());
    }

    @Override
    public void afterConnectionClosed(final WebSocketSession session, final CloseStatus status) {
        broadcaster.deregister(session);
    }

    @Override
    protected void handleTextMessage(final WebSocketSession session, final TextMessage message) {
        // The dashboard does not send messages, it only listens. We accept and ignore.
    }

    private Optional<Tenant> resolveTenant(final WebSocketSession session) {
        final List<String> headerKey = session.getHandshakeHeaders().get("X-API-Key");
        String apiKey = (headerKey != null && !headerKey.isEmpty()) ? headerKey.get(0) : null;
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = queryParam(session.getUri(), "key").orElse(null);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return tenants.findByApiKeyHash(ApiKeyHasher.hash(apiKey));
    }

    private static Optional<String> queryParam(final URI uri, final String name) {
        if (uri == null || uri.getQuery() == null) {
            return Optional.empty();
        }
        final Map<String, String> map = new HashMap<>();
        for (final String pair : uri.getQuery().split("&")) {
            final int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return Optional.ofNullable(map.get(name));
    }
}
