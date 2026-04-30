package com.merakilabs.taskq.core.port;

import com.merakilabs.taskq.core.domain.JobEvent;
import com.merakilabs.taskq.core.domain.JobId;
import java.util.List;

public interface JobEventLog {

    void append(JobEvent event);

    List<JobEvent> findByJobId(JobId jobId, int limit);
}
