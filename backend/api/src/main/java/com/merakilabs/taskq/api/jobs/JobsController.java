package com.merakilabs.taskq.api.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merakilabs.taskq.api.jobs.dto.ErrorResponse;
import com.merakilabs.taskq.api.jobs.dto.JobResponse;
import com.merakilabs.taskq.api.jobs.dto.PageResponse;
import com.merakilabs.taskq.api.jobs.dto.SubmitJobRequest;
import com.merakilabs.taskq.api.jobs.dto.SubmitJobResponse;
import com.merakilabs.taskq.api.security.TenantContext;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.Priority;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.SubmitJobCommand;
import com.merakilabs.taskq.core.domain.SubmitResult;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.error.JobNotFoundException;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.worker.config.TaskqProperties;
import com.merakilabs.taskq.worker.submission.JobSubmissionService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/v1/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
public class JobsController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final JobSubmissionService submission;
    private final JobRepository jobs;
    private final ObjectMapper mapper;
    private final TaskqProperties props;

    public JobsController(
            final JobSubmissionService submission,
            final JobRepository jobs,
            final ObjectMapper mapper,
            final TaskqProperties props) {
        this.submission = submission;
        this.jobs = jobs;
        this.mapper = mapper;
        this.props = props;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> submit(
            @Valid @RequestBody final SubmitJobRequest req,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) final String idempotencyKey) {
        final Tenant tenant = TenantContext.currentTenant();

        final byte[] payload = serialise(req);
        final SubmitJobCommand cmd = new SubmitJobCommand(
                tenant.id(),
                new QueueName(req.queue()),
                req.type(),
                payload,
                req.priority() == null ? Priority.NORMAL : new Priority(req.priority()),
                req.maxAttempts() == null ? props.retry().defaultMaxAttempts() : req.maxAttempts(),
                Optional.ofNullable(req.runAt()),
                Optional.ofNullable(idempotencyKey).filter(s -> !s.isBlank()));

        final SubmitResult result = submission.submit(cmd);
        return switch (result) {
            case SubmitResult.Created c -> ResponseEntity.accepted()
                    .header(HttpHeaders.LOCATION, "/v1/jobs/" + c.job().id().value())
                    .body(new SubmitJobResponse(
                            c.job().id().value(),
                            c.job().status().name(),
                            false,
                            c.job().scheduledAt()));
            case SubmitResult.IdempotentReplay r -> ResponseEntity.ok(new SubmitJobResponse(
                    r.existingJob().id().value(),
                    r.existingJob().status().name(),
                    true,
                    r.existingJob().scheduledAt()));
            case SubmitResult.IdempotencyConflict c -> ResponseEntity.unprocessableEntity()
                    .body(ErrorResponse.of(
                            "idempotency_conflict",
                            c.reason(),
                            Map.of("existing_job_id", c.existingJobId().value())));
            case SubmitResult.PayloadTooLarge p -> ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ErrorResponse.of(
                            "payload_too_large",
                            "Payload exceeds " + p.limitBytes() + " bytes"));
            case SubmitResult.RateLimited rl -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(rl.retryAfter().toSeconds()))
                    .body(ErrorResponse.of("rate_limited", "tenant rate limit exceeded"));
            case SubmitResult.TenantInactive t -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ErrorResponse.of("tenant_inactive", "tenant is suspended"));
        };
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable final UUID id) {
        final Tenant tenant = TenantContext.currentTenant();
        final var job = jobs.findById(new JobId(id))
                .filter(j -> j.tenantId().equals(tenant.id()))
                .orElseThrow(() -> new JobNotFoundException(new JobId(id)));
        return JobResponse.from(job, mapper);
    }

    @GetMapping
    public PageResponse<JobResponse> list(
            @RequestParam(required = false) final String status,
            @RequestParam(defaultValue = "50") final int limit,
            @RequestParam(defaultValue = "0") final int offset) {
        final Tenant tenant = TenantContext.currentTenant();
        final int boundedLimit = Math.min(Math.max(limit, 1), 200);
        final int boundedOffset = Math.max(offset, 0);
        final Optional<JobStatus> statusFilter = Optional.ofNullable(status).map(JobStatus::valueOf);
        final var rows = jobs.findByTenant(tenant.id(), statusFilter, boundedLimit, boundedOffset);
        final List<JobResponse> items = rows.stream().map(j -> JobResponse.from(j, mapper)).toList();
        final long total = statusFilter
                .map(s -> jobs.countByStatus(s, Optional.of(tenant.id()), Optional.empty()))
                .orElse((long) items.size());
        return new PageResponse<>(items, boundedLimit, boundedOffset, total);
    }

    private byte[] serialise(final SubmitJobRequest req) {
        try {
            return mapper.writeValueAsBytes(req.payload());
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to serialise payload", e);
        }
    }
}
