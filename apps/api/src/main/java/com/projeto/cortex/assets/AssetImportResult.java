package com.projeto.cortex.assets;

public record AssetImportResult(
        String syncRunId,
        String sourceDatabase,
        String sourceTable,
        String status,
        int recordsRead,
        int recordsProcessed,
        int recordsInserted,
        int recordsUpdated,
        int recordsDeactivated,
        String errorMessage
) {}
