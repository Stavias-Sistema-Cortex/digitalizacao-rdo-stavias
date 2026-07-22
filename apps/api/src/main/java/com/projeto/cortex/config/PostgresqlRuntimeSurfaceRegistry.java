package com.projeto.cortex.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Exact release ledger for the complete PostgreSQL operational runtime.
 *
 * <p>Runtime readiness is granted only when the immutable registry contains
 * exactly authentication, finance, memory/ontology, RDO and sync. Missing or
 * unexpected labels keep the normal runtime fail-closed.</p>
 */
public final class PostgresqlRuntimeSurfaceRegistry {

    private static final Set<String> CORTEX_RUNTIME_SURFACES = Set.of(
            "authentication",
            "finance",
            "memory-ontology",
            "rdo",
            "sync"
    );

    private final Set<String> releasedSurfaces;

    public PostgresqlRuntimeSurfaceRegistry() {
        this(CORTEX_RUNTIME_SURFACES);
    }

    PostgresqlRuntimeSurfaceRegistry(Set<String> releasedSurfaces) {
        this.releasedSurfaces = Collections.unmodifiableSet(
                new LinkedHashSet<>(releasedSurfaces)
        );
    }

    public Set<String> releasedSurfaces() {
        return releasedSurfaces;
    }

    boolean hasCompleteRuntimeSurfaceSet() {
        return releasedSurfaces.equals(CORTEX_RUNTIME_SURFACES);
    }
}
