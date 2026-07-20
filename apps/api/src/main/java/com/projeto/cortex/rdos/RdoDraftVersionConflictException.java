package com.projeto.cortex.rdos;

/** Conflito de versão detectado no próprio UPDATE atômico do rascunho. */
public final class RdoDraftVersionConflictException extends RuntimeException {

    private final long expectedVersion;
    private final long currentVersion;

    public RdoDraftVersionConflictException(
            long expectedVersion,
            long currentVersion
    ) {
        super("Conflito de versão do RDO.");
        this.expectedVersion = expectedVersion;
        this.currentVersion = currentVersion;
    }

    public long expectedVersion() {
        return expectedVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
