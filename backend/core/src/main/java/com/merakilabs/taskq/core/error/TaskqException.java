package com.merakilabs.taskq.core.error;

public class TaskqException extends RuntimeException {

    public TaskqException(final String message) {
        super(message);
    }

    public TaskqException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
