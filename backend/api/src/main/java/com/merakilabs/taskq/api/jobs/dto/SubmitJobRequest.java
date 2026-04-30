package com.merakilabs.taskq.api.jobs.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

public record SubmitJobRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9._:-]{1,64}$") String queue,
        @NotBlank String type,
        @NotNull JsonNode payload,
        @Min(0) @Max(1000) Integer priority,
        @Min(1) @Max(100) Integer maxAttempts,
        Instant runAt) {}
