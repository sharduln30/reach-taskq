package com.merakilabs.taskq.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class JobWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Test
    void echoFailGoesToDlqThenReplaySucceeds() throws Exception {
        final ResponseEntity<String> post = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"fail\"},\"priority\":50,\"maxAttempts\":1}");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final UUID id = UUID.fromString(objectMapper.readTree(post.getBody()).path("id").asText());
        drainUntilStatus(id, "DEAD");

        final ResponseEntity<String> dlq =
                rest.exchange("/v1/dlq", HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
        assertThat(dlq.getStatusCode()).isEqualTo(HttpStatus.OK);
        final JsonNode dlqPage = objectMapper.readTree(dlq.getBody());
        assertThat(dlqPage.path("total").asLong()).isGreaterThanOrEqualTo(1);

        final ResponseEntity<String> replay = rest.exchange(
                "/v1/dlq/" + id + "/replay",
                HttpMethod.POST,
                new HttpEntity<>("{\"payload\":{\"outcome\":\"success\"}}", jsonHeaders()),
                String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(replay.getBody()).path("status").asText()).isEqualTo("READY");
        drainUntilStatus(id, "SUCCEEDED");
    }

    @Test
    void flapRetriesThenSucceeds() throws Exception {
        final ResponseEntity<String> post = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"flap\",\"passOn\":2},\"priority\":50,\"maxAttempts\":8}");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final UUID id = UUID.fromString(objectMapper.readTree(post.getBody()).path("id").asText());
        drainUntilStatus(id, "SUCCEEDED");
    }
}
