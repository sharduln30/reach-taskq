package com.merakilabs.taskq.core.domain;

public enum JobStatus {
    PENDING,
    SCHEDULED,
    READY,
    LEASED,
    SUCCEEDED,
    FAILED,
    DEAD,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == DEAD || this == CANCELLED;
    }

    public boolean isInFlight() {
        return this == LEASED;
    }
}
