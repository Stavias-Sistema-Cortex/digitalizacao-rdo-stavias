package com.projeto.cortex.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class HealthController {

    private final RuntimeRevision revision;

    public HealthController(RuntimeRevision revision) {
        this.revision = revision;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "service", "cortex-api",
                "revision", revision.value(),
                "timestamp", Instant.now().toString()
        );
    }
}
