package com.merakilabs.taskq.api.jobs.dto;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        String error,
        String message,
        Instant timestamp,
        Map<String, Object> details) {

    public static ErrorResponse of(final String error, final String message) {
        return new ErrorResponse(error, message, Instant.now(), Map.of());
    }

    public static ErrorResponse of(final String error, final String message, final Map<String, Object> details) {
        return new ErrorResponse(error, message, Instant.now(), details);
    }
}
