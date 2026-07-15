package com.projeto.cortex.sync;

public final class SyncVersionConflictException extends RuntimeException {

    private final String entityType;
    private final String entityId;
    private final long baseVersion;
    private final long currentVersion;

    public SyncVersionConflictException(
            String entityType,
            String entityId,
            long baseVersion,
            long currentVersion
    ) {
        super("Conflito de versão.");
        this.entityType = entityType;
        this.entityId = entityId;
        this.baseVersion = baseVersion;
        this.currentVersion = currentVersion;
    }

    public String entityType() {
        return entityType;
    }

    public String entityId() {
        return entityId;
    }

    public long baseVersion() {
        return baseVersion;
    }

    public long currentVersion() {
        return currentVersion;
    }
}
