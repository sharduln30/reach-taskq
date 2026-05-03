package com.merakilabs.taskq.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.JobStatusBroadcaster;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tenant-scoped WebSocket broadcaster. Sessions register on connect with their tenant id; events
 * are fanned out only to sessions matching the event's tenant.
 *
 * <p>Implements {@link JobStatusBroadcaster} so the worker / submission service can call us
 * directly after each status transition (replaces the per-row {@code pg_notify} pipeline which
 * serialized inserts on the per-database notification queue lock).
 */
@Component
public class JobEventBroadcaster implements JobStatusBroadcaster {

    private static final Logger LOG = LoggerFactory.getLogger(JobEventBroadcaster.class);

    private final ObjectMapper mapper;
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> sessionsByTenant =
            new ConcurrentHashMap<>();

    public JobEventBroadcaster(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void register(final UUID tenantId, final WebSocketSession session) {
        sessionsByTenant
                .computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet())
                .add(session);
    }

    public void deregister(final WebSocketSession session) {
        sessionsByTenant.values().forEach(set -> set.remove(session));
    }

    public int sessionCount(final UUID tenantId) {
        return sessionsByTenant.getOrDefault(tenantId, Set.of()).size();
    }

    public void publishStatusChange(final JobStatusEvent event) {
        final Set<WebSocketSession> sessions = sessionsByTenant.getOrDefault(event.tenantId(), Set.of());
        if (sessions.isEmpty()) {
            return;
        }
        final String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (final IOException e) {
            LOG.warn("Failed to serialise WS event", e);
            return;
        }
        final TextMessage msg = new TextMessage(json);
        for (final WebSocketSession session : sessions) {
            sendQuietly(session, msg);
        }
    }

    @Override
    public void publish(
            final JobId jobId,
            final TenantId tenantId,
            final QueueName queue,
            final JobStatus status,
            final int attempt) {
        try {
            publishStatusChange(new JobStatusEvent(
                    jobId.value(), tenantId.value(), queue.value(), status.name(), attempt, Instant.now()));
        } catch (final RuntimeException re) {
            LOG.debug("WS broadcast failed (non-fatal)", re);
        }
    }

    private void sendQuietly(final WebSocketSession session, final TextMessage msg) {
        if (!session.isOpen()) {
            sessionsByTenant.values().forEach(set -> set.remove(session));
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(msg);
            }
        } catch (final IOException e) {
            LOG.debug("WS send failed for {}, dropping session", session.getId(), e);
            try {
                session.close();
            } catch (final IOException ignored) {
            }
            sessionsByTenant.values().forEach(set -> set.remove(session));
        }
    }
}
