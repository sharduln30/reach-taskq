package com.merakilabs.taskq.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class JobsApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void submitEchoAcceptedThenSucceeded() throws Exception {
        final ResponseEntity<String> post = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\"},\"priority\":50,\"maxAttempts\":5}");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final JsonNode created = objectMapper.readTree(post.getBody());
        final UUID id = UUID.fromString(created.path("id").asText());
        assertThat(created.path("status").asText()).isEqualTo("READY");
        drainUntilStatus(id, "SUCCEEDED");
    }

    @Test
    void idempotentReplayReturns200() throws Exception {
        final String key = "idem-" + UUID.randomUUID();
        final String body =
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\"},\"priority\":50}";
        final HttpHeaders headers = jsonHeaders();
        headers.set("Idempotency-Key", key);
        final HttpEntity<String> entity = new HttpEntity<>(body, headers);
        final ResponseEntity<String> first =
                rest.exchange("/v1/jobs", HttpMethod.POST, entity, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final UUID id = UUID.fromString(objectMapper.readTree(first.getBody()).path("id").asText());
        final ResponseEntity<String> second =
                rest.exchange("/v1/jobs", HttpMethod.POST, entity, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        final JsonNode replay = objectMapper.readTree(second.getBody());
        assertThat(replay.path("idempotentReplay").asBoolean()).isTrue();
        assertThat(UUID.fromString(replay.path("id").asText())).isEqualTo(id);
    }

    @Test
    void idempotencyConflictOnPayloadMismatch() throws Exception {
        final String key = "idem-conflict-" + UUID.randomUUID();
        final HttpHeaders firstHeaders = jsonHeaders();
        firstHeaders.set("Idempotency-Key", key);
        final HttpEntity<String> first = new HttpEntity<>(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"a\":1},\"priority\":50}", firstHeaders);
        assertThat(rest.exchange("/v1/jobs", HttpMethod.POST, first, String.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        final HttpHeaders secondHeaders = jsonHeaders();
        secondHeaders.set("Idempotency-Key", key);
        final HttpEntity<String> second = new HttpEntity<>(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"a\":2},\"priority\":50}", secondHeaders);
        final ResponseEntity<String> res =
                rest.exchange("/v1/jobs", HttpMethod.POST, second, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void scheduledJobReturnsScheduledStatus() throws Exception {
        final ResponseEntity<String> post = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\"},\"priority\":50,\"runAt\":\"2035-01-01T00:00:00Z\"}");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final JsonNode root = objectMapper.readTree(post.getBody());
        assertThat(root.path("status").asText()).isEqualTo("SCHEDULED");
    }

    @Test
    void missingApiKeyRejected() {
        final HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        final ResponseEntity<String> res = rest.exchange(
                "/v1/jobs",
                HttpMethod.POST,
                new HttpEntity<>("{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{}}", h),
                String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void listJobsAndQueueStats() throws Exception {
        postSubmit("{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\"},\"priority\":50}");
        final ResponseEntity<String> list =
                rest.exchange("/v1/jobs?limit=10", HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        final JsonNode page = objectMapper.readTree(list.getBody());
        assertThat(page.path("items").isArray()).isTrue();
        final ResponseEntity<String> stats = rest.exchange(
                "/v1/queues/default/stats",
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders()),
                String.class);
        assertThat(stats.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(stats.getBody()).path("queue").asText()).isEqualTo("default");
    }

    @Test
    void tenantsMe() throws Exception {
        final ResponseEntity<String> res =
                rest.exchange("/v1/tenants/me", HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(res.getBody()).path("name").asText()).isEqualTo("integration");
    }
}
