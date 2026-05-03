package com.merakilabs.taskq.app.integration;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merakilabs.taskq.worker.relay.OutboxRelay;
import com.merakilabs.taskq.worker.runtime.ScheduledJobPromoter;
import com.merakilabs.taskq.worker.runtime.WorkerRuntime;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String API_KEY = "integration-test-api-key-do-not-use-prod";

    /**
     * Singleton Postgres container shared by every integration test in this JVM.
     * We do NOT rely on JUnit's {@code @Container/@Testcontainers} lifecycle because
     * that restarts the container per test class, which causes Hikari to point at a
     * dead JDBC port for the second class onward (Ryuk is also disabled in CI/local
     * Colima setups, so cleanup happens at JVM shutdown anyway).
     */
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("taskq")
                    .withUsername("taskq")
                    .withPassword("test")
                    .withReuse(true);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(final DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("taskq.broker", () -> "postgres");
        r.add("taskq.dev.seed-tenant", () -> "true");
        r.add("taskq.dev.seed-tenant-name", () -> "integration");
        r.add("taskq.dev.seed-tenant-api-key", () -> API_KEY);
        r.add("taskq.outbox.poll-interval-ms", () -> 20);
        r.add("taskq.scheduler.poll-interval-ms", () -> 50);
        r.add("taskq.worker.lease-reaper-interval-seconds", () -> 600);
        r.add("taskq.retry.backoff-base-ms", () -> 5L);
        r.add("taskq.retry.backoff-max-ms", () -> 80L);
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected WorkerRuntime workerRuntime;

    @Autowired
    protected OutboxRelay outboxRelay;

    @Autowired
    protected ScheduledJobPromoter scheduledJobPromoter;

    @BeforeEach
    void clearMdc() {
        org.slf4j.MDC.clear();
    }

    protected HttpHeaders jsonHeaders() {
        final HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", API_KEY);
        return h;
    }

    protected HttpHeaders jsonHeaders(final String apiKey) {
        final HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-API-Key", apiKey);
        return h;
    }

    protected ResponseEntity<String> postSubmit(final String jsonBody) {
        return rest.exchange(
                "/v1/jobs", HttpMethod.POST, new HttpEntity<>(jsonBody, jsonHeaders()), String.class);
    }

    protected ResponseEntity<String> postSubmit(final String jsonBody, final String apiKey) {
        return rest.exchange(
                "/v1/jobs", HttpMethod.POST, new HttpEntity<>(jsonBody, jsonHeaders(apiKey)), String.class);
    }

    protected ResponseEntity<String> getJobRaw(final UUID id) {
        return rest.exchange("/v1/jobs/" + id, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
    }

    protected ResponseEntity<String> getJobRaw(final UUID id, final String apiKey) {
        return rest.exchange(
                "/v1/jobs/" + id, HttpMethod.GET, new HttpEntity<>(jsonHeaders(apiKey)), String.class);
    }

    protected void drainUntilStatus(final UUID jobId, final String expected) {
        await().atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(60))
                .until(() -> {
                    outboxRelay.pollOnce();
                    workerRuntime.pullAndDispatchOnce();
                    scheduledJobPromoter.tickOnce();
                    try {
                        final ResponseEntity<String> res = rest.exchange(
                                "/v1/jobs/" + jobId,
                                HttpMethod.GET,
                                new HttpEntity<>(jsonHeaders()),
                                String.class);
                        if (!res.getStatusCode().is2xxSuccessful() || res.getBody() == null) {
                            return false;
                        }
                        final JsonNode root = objectMapper.readTree(res.getBody());
                        return expected.equals(root.path("status").asText());
                    } catch (final Exception e) {
                        return false;
                    }
                });
    }
}
