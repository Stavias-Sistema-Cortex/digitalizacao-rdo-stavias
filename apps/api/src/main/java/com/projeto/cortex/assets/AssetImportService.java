package com.projeto.cortex.assets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AssetImportService {

    private static final String CONNECTOR_NAME = "zld_asset_import";
    private static final String SOURCE_DATABASE = "dbstavias_zld";
    private static final String SOURCE_TABLE = "ativos";

    private final JdbcTemplate cortexJdbcTemplate;

    @Value("${cortex.sources.zld.url:}")
    private String zldUrl;

    @Value("${cortex.sources.zld.username:}")
    private String zldUsername;

    @Value("${cortex.sources.zld.password:}")
    private String zldPassword;

    public AssetImportService(JdbcTemplate cortexJdbcTemplate) {
        this.cortexJdbcTemplate = cortexJdbcTemplate;
    }

    public AssetImportResult importFromZldAtivos() {
        String syncRunId = UUID.randomUUID().toString();

        int recordsRead = 0;
        int recordsInserted = 0;
        int recordsUpdated = 0;
        int recordsDeactivated = 0;

        boolean runCreated = false;

        try {
            createSyncRun(syncRunId);
            runCreated = true;

            validateZldConfig();

            String selectSql = """
                    SELECT
                        id,
                        prefixo,
                        tipo,
                        modelo
                    FROM ativos
                    ORDER BY id
                    """;

            String upsertSql = """
                    INSERT INTO asset (
                        id,
                        source_database,
                        source_table,
                        source_pk,
                        external_code,
                        name,
                        category,
                        active,
                        source_hash,
                        row_version
                    )
                    VALUES (
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        ?,
                        1,
                        ?,
                        0
                    )
                    ON DUPLICATE KEY UPDATE
                        external_code = VALUES(external_code),
                        name = VALUES(name),
                        category = VALUES(category),
                        active = 1,
                        deleted_at = NULL,
                        last_seen_at = CURRENT_TIMESTAMP(6),
                        row_version = IF(COALESCE(source_hash, '') <> VALUES(source_hash), row_version + 1, row_version),
                        source_hash = VALUES(source_hash)
                    """;

            try (
                    Connection connection = DriverManager.getConnection(zldUrl, zldUsername, zldPassword);
                    Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(selectSql)
            ) {
                while (resultSet.next()) {
                    String sourcePk = resultSet.getString("id");
                    String prefixo = resultSet.getString("prefixo");
                    String tipo = resultSet.getString("tipo");
                    String modelo = resultSet.getString("modelo");

                    String assetId = stableAssetId(sourcePk);
                    String name = normalizeName(modelo, prefixo);
                    String sourceHash = hashRow(sourcePk, prefixo, tipo, modelo);

                    String existingHash = findExistingSourceHash(sourcePk);

                    recordsRead++;

                    if (existingHash == null) {
                        recordsInserted++;
                    } else if (!existingHash.equals(sourceHash)) {
                        recordsUpdated++;
                    }

                    cortexJdbcTemplate.update(
                            upsertSql,
                            assetId,
                            SOURCE_DATABASE,
                            SOURCE_TABLE,
                            sourcePk,
                            prefixo,
                            name,
                            tipo,
                            sourceHash
                    );
                }
            }

            finishSyncRunSuccess(syncRunId, recordsRead, recordsInserted, recordsUpdated, recordsDeactivated);
            upsertCheckpointSuccess();

            return new AssetImportResult(
                    syncRunId,
                    SOURCE_DATABASE,
                    SOURCE_TABLE,
                    "SUCCESS",
                    recordsRead,
                    recordsRead,
                    recordsInserted,
                    recordsUpdated,
                    recordsDeactivated,
                    null
            );

        } catch (Exception exception) {
            String errorMessage = truncate(rootCauseMessage(exception), 1000);

            if (runCreated) {
                finishSyncRunFailureSafely(
                        syncRunId,
                        recordsRead,
                        recordsInserted,
                        recordsUpdated,
                        recordsDeactivated,
                        errorMessage
                );
                upsertCheckpointFailureSafely(errorMessage);
            }

            throw new IllegalStateException("Failed to import assets from dbstavias_zld.ativos", exception);
        }
    }

    public List<SyncRunResponse> listRecentRuns(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));

        String sql = """
                SELECT
                    id,
                    connector_name,
                    source_database,
                    source_table,
                    CAST(started_at AS CHAR) AS started_at,
                    CAST(finished_at AS CHAR) AS finished_at,
                    status,
                    records_read,
                    records_inserted,
                    records_updated,
                    records_deactivated,
                    error_message
                FROM source_sync_run
                ORDER BY started_at DESC
                LIMIT ?
                """;

        return cortexJdbcTemplate.query(
                sql,
                (resultSet, rowNumber) -> new SyncRunResponse(
                        resultSet.getString("id"),
                        resultSet.getString("connector_name"),
                        resultSet.getString("source_database"),
                        resultSet.getString("source_table"),
                        resultSet.getString("started_at"),
                        resultSet.getString("finished_at"),
                        resultSet.getString("status"),
                        resultSet.getInt("records_read"),
                        resultSet.getInt("records_inserted"),
                        resultSet.getInt("records_updated"),
                        resultSet.getInt("records_deactivated"),
                        resultSet.getString("error_message")
                ),
                safeLimit
        );
    }

    private void createSyncRun(String syncRunId) {
        String sql = """
                INSERT INTO source_sync_run (
                    id,
                    connector_name,
                    source_database,
                    source_table,
                    status
                )
                VALUES (?, ?, ?, ?, 'RUNNING')
                """;

        cortexJdbcTemplate.update(
                sql,
                syncRunId,
                CONNECTOR_NAME,
                SOURCE_DATABASE,
                SOURCE_TABLE
        );
    }

    private void finishSyncRunSuccess(
            String syncRunId,
            int recordsRead,
            int recordsInserted,
            int recordsUpdated,
            int recordsDeactivated
    ) {
        String sql = """
                UPDATE source_sync_run
                SET
                    finished_at = CURRENT_TIMESTAMP(6),
                    status = 'SUCCESS',
                    records_read = ?,
                    records_inserted = ?,
                    records_updated = ?,
                    records_deactivated = ?,
                    error_message = NULL
                WHERE id = ?
                """;

        cortexJdbcTemplate.update(
                sql,
                recordsRead,
                recordsInserted,
                recordsUpdated,
                recordsDeactivated,
                syncRunId
        );
    }

    private void finishSyncRunFailureSafely(
            String syncRunId,
            int recordsRead,
            int recordsInserted,
            int recordsUpdated,
            int recordsDeactivated,
            String errorMessage
    ) {
        try {
            String sql = """
                    UPDATE source_sync_run
                    SET
                        finished_at = CURRENT_TIMESTAMP(6),
                        status = 'FAILED',
                        records_read = ?,
                        records_inserted = ?,
                        records_updated = ?,
                        records_deactivated = ?,
                        error_message = ?
                    WHERE id = ?
                    """;

            cortexJdbcTemplate.update(
                    sql,
                    recordsRead,
                    recordsInserted,
                    recordsUpdated,
                    recordsDeactivated,
                    errorMessage,
                    syncRunId
            );
        } catch (Exception ignored) {
            // Avoid masking the original import error.
        }
    }

    private void upsertCheckpointSuccess() {
        String sql = """
                INSERT INTO source_sync_checkpoint (
                    id,
                    connector_name,
                    source_database,
                    source_table,
                    last_full_scan_at,
                    last_success_at,
                    last_error_message
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    CURRENT_TIMESTAMP(6),
                    CURRENT_TIMESTAMP(6),
                    NULL
                )
                ON DUPLICATE KEY UPDATE
                    last_full_scan_at = CURRENT_TIMESTAMP(6),
                    last_success_at = CURRENT_TIMESTAMP(6),
                    last_error_message = NULL
                """;

        cortexJdbcTemplate.update(
                sql,
                UUID.randomUUID().toString(),
                CONNECTOR_NAME,
                SOURCE_DATABASE,
                SOURCE_TABLE
        );
    }

    private void upsertCheckpointFailureSafely(String errorMessage) {
        try {
            String sql = """
                    INSERT INTO source_sync_checkpoint (
                        id,
                        connector_name,
                        source_database,
                        source_table,
                        last_error_at,
                        last_error_message
                    )
                    VALUES (
                        ?,
                        ?,
                        ?,
                        ?,
                        CURRENT_TIMESTAMP(6),
                        ?
                    )
                    ON DUPLICATE KEY UPDATE
                        last_error_at = CURRENT_TIMESTAMP(6),
                        last_error_message = VALUES(last_error_message)
                    """;

            cortexJdbcTemplate.update(
                    sql,
                    UUID.randomUUID().toString(),
                    CONNECTOR_NAME,
                    SOURCE_DATABASE,
                    SOURCE_TABLE,
                    errorMessage
            );
        } catch (Exception ignored) {
            // Avoid masking the original import error.
        }
    }

    private String findExistingSourceHash(String sourcePk) {
        String sql = """
                SELECT source_hash
                FROM asset
                WHERE source_database = ?
                  AND source_table = ?
                  AND source_pk = ?
                """;

        return cortexJdbcTemplate.query(
                sql,
                resultSet -> resultSet.next() ? resultSet.getString("source_hash") : null,
                SOURCE_DATABASE,
                SOURCE_TABLE,
                sourcePk
        );
    }

    private void validateZldConfig() {
        if (isBlank(zldUrl) || isBlank(zldUsername) || isBlank(zldPassword)) {
            throw new IllegalStateException("Missing ZLD database configuration. Set ZLD_DB_URL, ZLD_DB_USER, and ZLD_DB_PASSWORD.");
        }
    }

    private String stableAssetId(String sourcePk) {
        return UUID.nameUUIDFromBytes((SOURCE_DATABASE + "." + SOURCE_TABLE + ":" + sourcePk).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizeName(String modelo, String prefixo) {
        if (!isBlank(modelo)) return modelo.trim();
        if (!isBlank(prefixo)) return prefixo.trim();
        return "Ativo sem modelo";
    }

    private String hashRow(String sourcePk, String prefixo, String tipo, String modelo) throws Exception {
        String raw = nullToEmpty(sourcePk) + "|" + nullToEmpty(prefixo) + "|" + nullToEmpty(tipo) + "|" + nullToEmpty(modelo);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    private String rootCauseMessage(Exception exception) {
        Throwable current = exception;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();

        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }

        return current.getClass().getSimpleName() + ": " + message;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
