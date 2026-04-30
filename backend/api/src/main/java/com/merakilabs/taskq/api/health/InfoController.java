package com.merakilabs.taskq.api.health;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/info")
public class InfoController {

    private final String appName;
    private final String version;

    public InfoController(
            @Value("${spring.application.name}") final String appName,
            @Value("${project.version:0.1.0-SNAPSHOT}") final String version) {
        this.appName = appName;
        this.version = version;
    }

    @GetMapping
    public Map<String, Object> info() {
        return Map.of(
                "name", appName,
                "version", version,
                "now", Instant.now().toString());
    }
}
