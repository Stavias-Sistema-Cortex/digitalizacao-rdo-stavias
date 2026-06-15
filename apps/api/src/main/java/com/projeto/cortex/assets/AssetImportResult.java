package com.projeto.cortex.assets;

public record AssetImportResult(
        String sourceDatabase,
        String sourceTable,
        int recordsRead,
        int recordsProcessed
) {
}
