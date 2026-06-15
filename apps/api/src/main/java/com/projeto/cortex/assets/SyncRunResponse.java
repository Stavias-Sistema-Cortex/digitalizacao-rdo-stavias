package com.projeto.cortex.assets;

public record SyncRunResponse(
        String id,
        String connectorName,
        String sourceDatabase,
        String sourceTable,
        String startedAt,
        String finishedAt,
        String status,
        int recordsRead,
        int recordsInserted,
        int recordsUpdated,
        int recordsDeactivated,
        String errorMessage
) {}
