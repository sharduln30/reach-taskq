package com.merakilabs.taskq.api.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merakilabs.taskq.api.jobs.dto.DlqEntryResponse;
import com.merakilabs.taskq.api.jobs.dto.JobResponse;
import com.merakilabs.taskq.api.jobs.dto.PageResponse;
import com.merakilabs.taskq.api.security.TenantContext;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.error.JobNotFoundException;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.persistence.JdbcDlqReasonReader;
import com.merakilabs.taskq.worker.replay.DlqReplayService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/v1/dlq", produces = MediaType.APPLICATION_JSON_VALUE)
public class DlqController {

    private final JobRepository jobs;
    private final DlqReplayService replay;
    private final ObjectMapper mapper;
    private final JdbcDlqReasonReader reasons;

    public DlqController(
            final JobRepository jobs,
            final DlqReplayService replay,
            final ObjectMapper mapper,
            final JdbcDlqReasonReader reasons) {
        this.jobs = jobs;
        this.replay = replay;
        this.mapper = mapper;
        this.reasons = reasons;
    }

    @GetMapping
    public PageResponse<DlqEntryResponse> list(
            @RequestParam(defaultValue = "50") final int limit,
            @RequestParam(defaultValue = "0") final int offset) {
        final Tenant t = TenantContext.currentTenant();
        final int boundedLimit = Math.min(Math.max(limit, 1), 200);
        final int boundedOffset = Math.max(offset, 0);
        final var jobsPage = jobs.findByTenant(
                t.id(), Optional.of(JobStatus.DEAD), boundedLimit, boundedOffset);
        final List<UUID> ids = jobsPage.stream().map(j -> j.id().value()).toList();
        final Map<UUID, JdbcDlqReasonReader.Row> reasonByJob = reasons.findByJobIds(ids);
        final List<DlqEntryResponse> items = jobsPage.stream()
                .map(j -> {
                    final var r = reasonByJob.get(j.id().value());
                    final Instant deadAt = r == null ? null : r.deadAt();
                    final String reason = r == null ? j.lastError() : r.reason();
                    return DlqEntryResponse.from(j, mapper, deadAt, reason);
                })
                .toList();
        final long total =
                jobs.countByStatus(JobStatus.DEAD, Optional.of(t.id()), Optional.empty());
        return new PageResponse<>(items, boundedLimit, boundedOffset, total);
    }

    /**
     * Replay a DEAD job. Body is optional; provide
     * {@code {"payload": {...new payload...}}} to override the original payload
     * (useful when the original payload itself caused the failure, e.g. a
     * malformed request the consumer can now handle, or for live demos).
     */
    @PostMapping(value = "/{id}/replay", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    public JobResponse replayOne(
            @PathVariable final UUID id,
            @RequestBody(required = false) final ReplayRequest body) {
        final Tenant tenant = TenantContext.currentTenant();
        final var job = jobs.findById(new JobId(id))
                .filter(j -> j.tenantId().equals(tenant.id()))
                .orElseThrow(() -> new JobNotFoundException(new JobId(id)));

        final byte[] payloadOverride = (body != null && body.payload() != null)
                ? body.payload().toString().getBytes(StandardCharsets.UTF_8)
                : null;

        final var replayed = replay.replay(job.id(), payloadOverride);
        return JobResponse.from(replayed, mapper);
    }

    public record ReplayRequest(JsonNode payload) {}
}
