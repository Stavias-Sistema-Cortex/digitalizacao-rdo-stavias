package com.projeto.cortex.common;

import java.util.Map;

/** Database and authentication readiness contract exposed by the API. */
public interface RuntimeReadiness {

    void verifyRuntimeReadiness();

    default String readinessStatus() {
        return "READY";
    }

    default Map<String, String> publicEvidence() {
        return Map.of();
    }

    default Map<String, String> verifiedPublicEvidence() {
        verifyRuntimeReadiness();
        return publicEvidence();
    }
}
