package com.merakilabs.taskq.api.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merakilabs.taskq.api.jobs.dto.JobResponse;
import com.merakilabs.taskq.api.jobs.dto.PageResponse;
import com.merakilabs.taskq.api.security.TenantContext;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.error.JobNotFoundException;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.worker.replay.DlqReplayService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/v1/dlq", produces = MediaType.APPLICATION_JSON_VALUE)
public class DlqController {

    private final JobRepository jobs;
    private final DlqReplayService replay;
    private final ObjectMapper mapper;

    public DlqController(
            final JobRepository jobs, final DlqReplayService replay, final ObjectMapper mapper) {
        this.jobs = jobs;
        this.replay = replay;
        this.mapper = mapper;
    }

    @GetMapping
    public PageResponse<JobResponse> list(
            @RequestParam(defaultValue = "50") final int limit,
            @RequestParam(defaultValue = "0") final int offset) {
        final Tenant t = TenantContext.currentTenant();
        final int boundedLimit = Math.min(Math.max(limit, 1), 200);
        final int boundedOffset = Math.max(offset, 0);
        final List<JobResponse> items = jobs
                .findByTenant(t.id(), Optional.of(JobStatus.DEAD), boundedLimit, boundedOffset)
                .stream()
                .map(j -> JobResponse.from(j, mapper))
                .toList();
        final long total =
                jobs.countByStatus(JobStatus.DEAD, Optional.of(t.id()), Optional.empty());
        return new PageResponse<>(items, boundedLimit, boundedOffset, total);
    }

    @PostMapping("/{id}/replay")
    public JobResponse replayOne(@PathVariable final UUID id) {
        final Tenant tenant = TenantContext.currentTenant();
        final var job = jobs.findById(new JobId(id))
                .filter(j -> j.tenantId().equals(tenant.id()))
                .orElseThrow(() -> new JobNotFoundException(new JobId(id)));
        final var replayed = replay.replay(job.id());
        return JobResponse.from(replayed, mapper);
    }
}
