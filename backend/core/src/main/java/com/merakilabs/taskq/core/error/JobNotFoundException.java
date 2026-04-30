package com.merakilabs.taskq.core.error;

import com.merakilabs.taskq.core.domain.JobId;

public class JobNotFoundException extends TaskqException {

    private final JobId jobId;

    public JobNotFoundException(final JobId jobId) {
        super("Job not found: " + jobId);
        this.jobId = jobId;
    }

    public JobId jobId() {
        return jobId;
    }
}
