package com.merakilabs.taskq.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.merakilabs.taskq.api.security.ApiKeyHasher;
import com.merakilabs.taskq.core.domain.Tenant;
import com.merakilabs.taskq.core.domain.TenantId;
import com.merakilabs.taskq.core.port.TenantRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class TenantIsolationIntegrationTest extends AbstractIntegrationTest {

    private static final String OTHER_KEY = "other-tenant-api-key-integration-01";

    @Autowired
    private TenantRepository tenants;

    @Test
    void otherTenantCannotReadForeignJob() throws Exception {
        final ResponseEntity<String> post = postSubmit(
                "{\"queue\":\"default\",\"type\":\"echo\",\"payload\":{\"outcome\":\"success\"},\"priority\":50}");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        final UUID jobId = UUID.fromString(objectMapper.readTree(post.getBody()).path("id").asText());

        tenants.insert(new Tenant(
                new TenantId(UUID.randomUUID()),
                "other-" + UUID.randomUUID(),
                ApiKeyHasher.hash(OTHER_KEY),
                100,
                200,
                50,
                true,
                Instant.now()));

        final ResponseEntity<String> foreign = getJobRaw(jobId, OTHER_KEY);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        final JsonNode err = objectMapper.readTree(foreign.getBody());
        assertThat(err.path("error").asText()).isEqualTo("not_found");
    }
}
