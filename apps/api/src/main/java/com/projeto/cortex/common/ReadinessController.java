package com.projeto.cortex.common;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ReadinessController {

    private final RuntimeReadiness readiness;

    public ReadinessController(RuntimeReadiness readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/api/readiness")
    public Map<String, String> readiness() {
        readiness.verifyRuntimeReadiness();
        return Map.of(
                "status", readiness.readinessStatus(),
                "service", "cortex-api",
                "timestamp", Instant.now().toString()
        );
    }
}
