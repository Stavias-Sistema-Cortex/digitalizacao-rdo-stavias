package com.projeto.cortex.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DeploymentRevisionEndpointsTest {

    private static final String REVISION =
            "0123456789abcdef0123456789abcdef01234567";

    @Test
    void healthPublishesTheExactRuntimeRevision() {
        Map<String, String> response =
                new HealthController(new RuntimeRevision(REVISION)).health();

        assertThat(response).containsEntry("revision", REVISION);
    }

    @Test
    void readinessPublishesTheSameExactRuntimeRevision() {
        RuntimeReadiness ready = () -> {
        };
        Map<String, String> response =
                new ReadinessController(ready, new RuntimeRevision(REVISION))
                        .readiness();

        assertThat(response).containsEntry("revision", REVISION);
    }
}
