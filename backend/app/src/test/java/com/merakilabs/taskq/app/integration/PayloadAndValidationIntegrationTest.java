package com.merakilabs.taskq.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestPropertySource(properties = "taskq.payload.max-bytes=64")
class PayloadAndValidationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void payloadTooLarge() {
        final String big = "{\"x\":\"" + "a".repeat(200) + "\"}";
        final ResponseEntity<String> res = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":" + big + ",\"priority\":50}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void invalidQueuePattern() {
        final ResponseEntity<String> res = postSubmit(
                "{\"queue\":\"bad queue\",\"type\":\"echo\",\"payload\":{},\"priority\":50}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        final JsonNode root;
        try {
            root = objectMapper.readTree(res.getBody());
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
        assertThat(root.path("error").asText()).isEqualTo("validation_failed");
    }
}
