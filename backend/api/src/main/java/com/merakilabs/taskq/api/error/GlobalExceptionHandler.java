package com.merakilabs.taskq.api.error;

import com.merakilabs.taskq.api.jobs.dto.ErrorResponse;
import com.merakilabs.taskq.core.error.JobNotFoundException;
import com.merakilabs.taskq.core.error.TaskqException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<ErrorResponse> jobNotFound(final JobNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("not_found", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> illegalState(final IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("conflict", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badArg(final IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("bad_request", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(final MethodArgumentNotValidException ex) {
        final Map<String, Object> details = new HashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(fe -> details.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("validation_failed", "Request body validation failed", details));
    }

    @ExceptionHandler({
        AccessDeniedException.class,
        AuthenticationException.class,
        AuthenticationCredentialsNotFoundException.class
    })
    public ResponseEntity<ErrorResponse> unauthorised(final Exception ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("unauthorised", "Missing or invalid X-API-Key header"));
    }

    @ExceptionHandler(TaskqException.class)
    public ResponseEntity<ErrorResponse> taskq(final TaskqException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("processing_error", ex.getMessage()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> noHandler(final NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("not_found", "Route not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unhandled(final Exception ex, final HttpServletRequest req) {
        LOG.error("Unhandled error on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("internal_error", "Unexpected error"));
    }
}
