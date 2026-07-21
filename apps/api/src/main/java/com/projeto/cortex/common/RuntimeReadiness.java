package com.projeto.cortex.common;

/** Database and authentication readiness contract exposed by the API. */
public interface RuntimeReadiness {

    void verifyRuntimeReadiness();

    default String readinessStatus() {
        return "READY";
    }
}
