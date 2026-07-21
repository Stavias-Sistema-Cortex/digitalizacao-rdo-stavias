package com.projeto.cortex.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Explicit release ledger for PostgreSQL-safe operational slices.
 *
 * <p>The clean-start delivery deliberately registers none. A later vertical
 * slice must add an explicit release before the normal runtime can start.</p>
 */
public final class PostgresqlRuntimeSurfaceRegistry {

    private final Set<String> releasedSurfaces;

    public PostgresqlRuntimeSurfaceRegistry() {
        this(Set.of());
    }

    PostgresqlRuntimeSurfaceRegistry(Set<String> releasedSurfaces) {
        this.releasedSurfaces = Collections.unmodifiableSet(
                new LinkedHashSet<>(releasedSurfaces)
        );
    }

    public Set<String> releasedSurfaces() {
        return releasedSurfaces;
    }

    boolean hasReleasedOperationalSurface() {
        return !releasedSurfaces.isEmpty();
    }
}
