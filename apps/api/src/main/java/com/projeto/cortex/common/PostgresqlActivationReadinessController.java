package com.projeto.cortex.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Readiness surface for the isolated e-mail activation launcher.
 *
 * <p>The activation slice intentionally has no object storage or offline
 * signing key. Those dependencies remain mandatory for the normal production
 * runtime through {@link ReadinessController}.</p>
 */
@RestController
@Profile("postgresql-activation")
public final class PostgresqlActivationReadinessController {

    private final RuntimeReadiness readiness;
    private final RuntimeRevision revision;

    public PostgresqlActivationReadinessController(
            RuntimeReadiness readiness,
            RuntimeRevision revision
    ) {
        this.readiness = readiness;
        this.revision = revision;
    }

    @GetMapping("/api/readiness")
    public Map<String, String> readiness() {
        Map<String, String> verifiedEvidence =
                readiness.verifiedPublicEvidence();
        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", readiness.readinessStatus());
        response.put("service", "cortex-api");
        response.put("revision", revision.value());
        response.put("timestamp", Instant.now().toString());
        verifiedEvidence.forEach((key, value) -> {
            if (response.putIfAbsent(key, value) != null) {
                throw new IllegalStateException(
                        "Evidência pública de readiness conflitante."
                );
            }
        });
        return Map.copyOf(response);
    }
}
