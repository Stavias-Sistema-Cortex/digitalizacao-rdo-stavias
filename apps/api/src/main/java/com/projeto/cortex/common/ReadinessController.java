package com.projeto.cortex.common;

import com.projeto.cortex.auth.AuthReadinessIndicator;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ReadinessController {

    private final AuthReadinessIndicator readiness;

    public ReadinessController(AuthReadinessIndicator readiness) {
        this.readiness = readiness;
    }

    @GetMapping("/api/readiness")
    public Map<String, String> readiness() {
        readiness.verifyRuntimeReadiness();
        return Map.of(
                "status", "READY",
                "service", "cortex-api",
                "timestamp", Instant.now().toString()
        );
    }
}
