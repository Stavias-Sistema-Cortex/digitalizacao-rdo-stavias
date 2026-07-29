package com.projeto.cortex.storage;

/** Reversible object-storage readiness contract used by the public gate. */
public interface ObjectStorageReadiness {

    void verifyObjectStorageReadiness();

    default String readinessStatus() {
        return "READY";
    }
}
