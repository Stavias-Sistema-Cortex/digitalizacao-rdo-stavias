package com.projeto.cortex.common;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ReadinessController {

    private final RuntimeReadiness readiness;
    private final RuntimeRevision revision;

    public ReadinessController(
            RuntimeReadiness readiness,
            RuntimeRevision revision
    ) {
        this.readiness = readiness;
        this.revision = revision;
    }

    @GetMapping("/api/readiness")
    public Map<String, String> readiness() {
        readiness.verifyRuntimeReadiness();
        return Map.of(
                "status", readiness.readinessStatus(),
                "service", "cortex-api",
                "revision", revision.value(),
                "timestamp", Instant.now().toString()
        );
    }
}
