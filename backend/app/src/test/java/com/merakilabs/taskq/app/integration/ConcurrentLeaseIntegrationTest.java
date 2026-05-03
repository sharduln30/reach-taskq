package com.merakilabs.taskq.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.merakilabs.taskq.core.domain.JobId;
import com.merakilabs.taskq.core.domain.LeaseToken;
import com.merakilabs.taskq.core.domain.QueueName;
import com.merakilabs.taskq.core.port.JobBroker;
import com.merakilabs.taskq.core.port.JobRepository;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Two threads race to lease the same job. SKIP-LOCKED + the conditional UPDATE on
 * {@code status='READY'} guarantee exactly one wins. The other returns an empty
 * delivery batch. This is the core safety property of at-least-once with no double
 * execution.
 */
class ConcurrentLeaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JobBroker broker;

    @Autowired
    JobRepository jobRepository;

    @Test
    void onlyOneConsumerLeasesAJob() throws Exception {
        outboxRelay.pollOnce();
        for (int i = 0; i < 16; i++) {
            workerRuntime.pullAndDispatchOnce();
        }

        final ResponseEntity<String> post = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\"},\"priority\":50,\"maxAttempts\":3}");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final UUID id = UUID.fromString(objectMapper.readTree(post.getBody()).path("id").asText());

        outboxRelay.pollOnce();

        final ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            final Future<Long> a = exec.submit(() -> countLeases(broker, id));
            final Future<Long> b = exec.submit(() -> countLeases(broker, id));
            final long aCount = a.get(10, TimeUnit.SECONDS);
            final long bCount = b.get(10, TimeUnit.SECONDS);
            assertThat(aCount + bCount).as("exactly one consumer leased the job").isEqualTo(1L);
        } finally {
            exec.shutdownNow();
        }

        assertThat(jobRepository.findById(new JobId(id)).orElseThrow().status().name())
                .isEqualTo("LEASED");
    }

    @Test
    void conditionalUpdatePreventsDoubleLease() {
        final UUID jobId = UUID.randomUUID();
        final ResponseEntity<String> post = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\"},\"priority\":50,\"maxAttempts\":3}");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final UUID id = uuidFrom(post.getBody());

        final LeaseToken first = LeaseToken.generate();
        final LeaseToken second = LeaseToken.generate();
        final boolean a = jobRepository.lease(new JobId(id), first, java.time.Instant.now().plusSeconds(60));
        final boolean b = jobRepository.lease(new JobId(id), second, java.time.Instant.now().plusSeconds(60));
        assertThat(a).isTrue();
        assertThat(b).as("second lease attempt must not succeed").isFalse();
    }

    private static long countLeases(final JobBroker broker, final UUID jobId) {
        return broker
                .pull(QueueName.DEFAULT, "race-consumer-" + UUID.randomUUID(), 1, Duration.ofMillis(500))
                .stream()
                .filter(d -> d.jobId().value().equals(jobId))
                .count();
    }

    private UUID uuidFrom(final String responseBody) {
        try {
            return UUID.fromString(objectMapper.readTree(responseBody).path("id").asText());
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
