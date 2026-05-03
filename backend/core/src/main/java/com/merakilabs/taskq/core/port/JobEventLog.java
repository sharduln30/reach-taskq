package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.JobEvent;
import com.merakilabs.taskq.core.domain.JobId;
import java.util.List;

public interface JobEventLog {

    void append(JobEvent event);

    /**
     * Persist multiple events in a single round trip. Implementations SHOULD use a batched
     * {@code INSERT} (e.g. JDBC {@code batchUpdate}) so the per-job worker path can collapse
     * 2-3 sequential inserts (LEASED + SUCCEEDED, or LEASED + RETRY_SCHEDULED) into one
     * round trip.
     *
     * <p>Default implementation falls back to looping {@link #append} so existing tests don't
     * need to add a no-op override.
     */
    default void appendAll(List<JobEvent> events) {
        for (JobEvent e : events) {
            append(e);
        }
    }

    List<JobEvent> findByJobId(JobId jobId, int limit);
}
