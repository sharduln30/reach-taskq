package com.merakilabs.taskq.api.jobs;

import com.merakilabs.taskq.api.security.TenantContext;
import com.merakilabs.taskq.core.domain.JobStatus;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.port.JobBroker;
import com.merakilabs.taskq.core.port.JobRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/v1/queues", produces = MediaType.APPLICATION_JSON_VALUE)
public class QueueStatsController {

    private final JobBroker broker;
    private final JobRepository jobs;

    public QueueStatsController(final JobBroker broker, final JobRepository jobs) {
        this.broker = broker;
        this.jobs = jobs;
    }

    @GetMapping("/{name}/stats")
    public Map<String, Object> stats(@PathVariable final String name) {
        final Tenant tenant = TenantContext.currentTenant();
        final QueueName q = new QueueName(name);
        return Map.of(
                "queue", name,
                "tenant", tenant.id().value(),
                "ready", broker.readyDepth(q),
                "tenant_pending", jobs.countByStatus(JobStatus.PENDING, Optional.of(tenant.id()), Optional.of(q)),
                "tenant_ready", jobs.countByStatus(JobStatus.READY, Optional.of(tenant.id()), Optional.of(q)),
                "tenant_scheduled", jobs.countByStatus(JobStatus.SCHEDULED, Optional.of(tenant.id()), Optional.of(q)),
                "tenant_leased", jobs.countByStatus(JobStatus.LEASED, Optional.of(tenant.id()), Optional.of(q)),
                "tenant_succeeded", jobs.countByStatus(JobStatus.SUCCEEDED, Optional.of(tenant.id()), Optional.of(q)),
                "tenant_failed", jobs.countByStatus(JobStatus.FAILED, Optional.of(tenant.id()), Optional.of(q)),
                "tenant_dead", jobs.countByStatus(JobStatus.DEAD, Optional.of(tenant.id()), Optional.of(q)));
    }
}
