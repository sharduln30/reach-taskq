package com.merakilabs.taskq.worker.runtime;

import com.merakilabs.taskq.core.port.JobHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers = new HashMap<>();

    public JobHandlerRegistry(final java.util.List<JobHandler> discovered) {
        for (final JobHandler h : discovered) {
            final JobHandler prev = handlers.putIfAbsent(h.type(), h);
            if (prev != null) {
                throw new IllegalStateException("Duplicate JobHandler for type: " + h.type());
            }
        }
    }

    public Optional<JobHandler> resolve(final String type) {
        return Optional.ofNullable(handlers.get(type));
    }
}
