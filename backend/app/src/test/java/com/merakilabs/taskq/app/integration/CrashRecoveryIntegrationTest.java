package com.merakilabs.taskq.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.merakilabs.taskq.core.domain.Job;
import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.port.JobRepository;
import com.merakilabs.taskq.worker.runtime.LeaseReaper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Simulates a crashed worker: we manually lease a job with a lease that has already
 * expired, then trigger the reaper. The job should come back to READY and re-process
 * to SUCCEEDED, i.e. crashes don't lose work.
 */
class CrashRecoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JobRepository jobRepository;

    @Autowired
    LeaseReaper leaseReaper;

    @Test
    void expiredLeaseIsReapedAndJobCompletes() throws Exception {
        final ResponseEntity<String> post = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\"},\"priority\":50,\"maxAttempts\":3}");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final UUID id = UUID.fromString(objectMapper.readTree(post.getBody()).path("id").asText());

        final LeaseToken token = LeaseToken.generate();
        final boolean leased =
                jobRepository.lease(new JobId(id), token, Instant.now().minusSeconds(60));
        assertThat(leased).as("manual lease must succeed on a freshly READY job").isTrue();

        final Job leasedJob = jobRepository.findById(new JobId(id)).orElseThrow();
        assertThat(leasedJob.status().name()).isEqualTo("LEASED");

        leaseReaper.reapOnce();

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    outboxRelay.pollOnce();
                    workerRuntime.pullAndDispatchOnce();
                    final ResponseEntity<String> res =
                            rest.exchange("/v1/jobs/" + id, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
                    if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                        return false;
                    }
                    final JsonNode root = objectMapper.readTree(res.getBody());
                    return "SUCCEEDED".equals(root.path("status").asText());
                });
    }

    @Test
    void expiredLeaseOnLastAttemptGoesToDead() throws Exception {
        final ResponseEntity<String> post = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\"},\"priority\":50,\"maxAttempts\":1}");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final UUID id = UUID.fromString(objectMapper.readTree(post.getBody()).path("id").asText());

        final LeaseToken token = LeaseToken.generate();
        assertThat(jobRepository.lease(new JobId(id), token, Instant.now().minusSeconds(60))).isTrue();

        leaseReaper.reapOnce();

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    final ResponseEntity<String> res =
                            rest.exchange("/v1/jobs/" + id, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
                    if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                        return false;
                    }
                    final JsonNode root = objectMapper.readTree(res.getBody());
                    return "DEAD".equals(root.path("status").asText());
                });
    }
}
