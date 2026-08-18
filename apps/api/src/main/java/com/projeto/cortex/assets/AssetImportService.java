package com.projeto.cortex.assets;

import com.projeto.cortex.integracoes.SourceImportRunLock;
import com.projeto.cortex.integracoes.ZeladoriaAssetSnapshot;
import com.projeto.cortex.integracoes.ZeladoriaSourceAdapter;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AssetImportService {

    private static final String CONNECTOR_NAME = "zld_asset_import";
    private static final String SOURCE_DATABASE = "dbstavias_zld";
    private static final String SOURCE_TABLE = "ativos";
    private static final int SNAPSHOT_PAGE_SIZE = 500;
    private static final String PUBLIC_FAILURE_MESSAGE =
            "Falha ao importar ativos da Zeladoria.";
    private static final String SOURCE_FAILURE_MESSAGE =
            "Falha ao ler snapshot completo da Zeladoria.";
    private static final String APPLY_FAILURE_MESSAGE =
            "Falha ao aplicar snapshot da Zeladoria.";
    private static final String LOCK_FAILURE_MESSAGE =
            "Sincronizacao Zeladoria indisponivel.";

    private final JdbcTemplate cortexJdbcTemplate;
    private final ZeladoriaSourceAdapter zeladoriaSourceAdapter;
    private final CortexOperationalMemoryService memoryService;
    private final TransactionTemplate requiresNewTransactions;
    private final SourceImportRunLock runLock;

    @Autowired
    public AssetImportService(
            JdbcTemplate cortexJdbcTemplate,
            ZeladoriaSourceAdapter zeladoriaSourceAdapter,
            CortexOperationalMemoryService memoryService,
            PlatformTransactionManager transactionManager
    ) {
        this(
                cortexJdbcTemplate,
                zeladoriaSourceAdapter,
                memoryService,
                requiresNewTransactionTemplate(transactionManager),
                postgresqlRunLock(cortexJdbcTemplate)
        );
    }

    /** Compatibility constructor for isolated integration tests. */
    public AssetImportService(
            JdbcTemplate cortexJdbcTemplate,
            ZeladoriaSourceAdapter zeladoriaSourceAdapter,
            CortexOperationalMemoryService memoryService
    ) {
        this(
                cortexJdbcTemplate,
                zeladoriaSourceAdapter,
                memoryService,
                transactionTemplateFor(cortexJdbcTemplate),
                postgresqlRunLock(cortexJdbcTemplate)
        );
    }

    AssetImportService(
            JdbcTemplate cortexJdbcTemplate,
            ZeladoriaSourceAdapter zeladoriaSourceAdapter,
            CortexOperationalMemoryService memoryService,
            TransactionTemplate requiresNewTransactions,
            SourceImportRunLock runLock
    ) {
        this.cortexJdbcTemplate = cortexJdbcTemplate;
        this.zeladoriaSourceAdapter = zeladoriaSourceAdapter;
        this.memoryService = memoryService;
        this.requiresNewTransactions = requiresNewTransactions;
        this.requiresNewTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        this.runLock = runLock;
    }

    public AssetImportResult importFromZldAtivos() {
        try (SourceImportRunLock.LockHandle ignored = runLock.acquire()) {
            return importWithLockAcquired();
        } catch (Exception ignored) {
            throw publicImportFailure();
        }
    }

    private AssetImportResult importWithLockAcquired() {
        String syncRunId = UUID.randomUUID().toString();
        int recordsRead = 0;
        boolean runCreated = false;
        String safeFailureMessage = SOURCE_FAILURE_MESSAGE;

        try {
            executeRequiresNew(() -> createSyncRun(syncRunId));
            runCreated = true;
            ZeladoriaAssetSnapshot snapshot =
                    zeladoriaSourceAdapter.fetchCompleteSnapshot(
                            SNAPSHOT_PAGE_SIZE
                    );
            recordsRead = snapshot == null ? 0 : snapshot.assets().size();
            PreparedSnapshot preparedSnapshot = prepareSnapshot(snapshot);
            safeFailureMessage = APPLY_FAILURE_MESSAGE;
            ImportApplicationResult applied = Objects.requireNonNull(
                    requiresNewTransactions.execute(status -> applySnapshot(
                            syncRunId,
                            preparedSnapshot
                    )),
                    "resultado da transacao Zeladoria"
            );

            return new AssetImportResult(
                    syncRunId,
                    SOURCE_DATABASE,
                    SOURCE_TABLE,
                    "SUCCESS",
                    recordsRead,
                    recordsRead,
                    applied.recordsInserted(),
                    applied.recordsUpdated(),
                    applied.recordsDeactivated(),
                    null
            );
        } catch (SnapshotValidationException exception) {
            safeFailureMessage = exception.getMessage();
        } catch (Exception ignored) {
            // Durable diagnostics use the safe phase message only.
        }

        if (runCreated) {
            int safeRecordsRead = recordsRead;
            String failureMessage = safeFailureMessage;
            executeRequiresNewBestEffort(() -> {
                finishSyncRunFailureSafely(
                        syncRunId,
                        safeRecordsRead,
                        0,
                        0,
                        0,
                        failureMessage
                );
                upsertCheckpointFailureSafely(failureMessage);
            });
            executeRequiresNewBestEffort(() -> {
                registrarImportacaoNaMemoria(
                        syncRunId,
                        "FAILED",
                        safeRecordsRead,
                        0,
                        0,
                        0,
                        failureMessage
                );
            });
        }
        throw publicImportFailure();
    }

    private PreparedSnapshot prepareSnapshot(
            ZeladoriaAssetSnapshot snapshot
    ) {
        if (snapshot == null || !snapshot.complete()) {
            throw snapshotConflict("completude");
        }

        Set<String> sourceIds = new HashSet<>();
        List<ZeladoriaSourceAdapter.AtivoZeladoriaRecord> prepared =
                snapshot.assets().stream().map(asset -> {
                    if (asset == null || isBlank(asset.id())) {
                        throw snapshotConflict("id de origem vazio");
                    }
                    String sourceId = asset.id().trim();
                    if (!sourceIds.add(sourceId)) {
                        throw snapshotConflict("id de origem duplicado");
                    }
                    return new ZeladoriaSourceAdapter.AtivoZeladoriaRecord(
                            sourceId,
                            asset.prefixo(),
                            asset.tipo(),
                            asset.modelo()
                    );
                }).toList();
        return new PreparedSnapshot(prepared);
    }

    private ImportApplicationResult applySnapshot(
            String syncRunId,
            PreparedSnapshot snapshot
    ) {
        Map<String, ExistingAsset> existingAssets = loadExistingAssets();
        Set<String> presentSourceIds = new HashSet<>();
        int recordsInserted = 0;
        int recordsUpdated = 0;
        int recordsDeactivated = 0;

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
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?, 0)
                ON CONFLICT (source_database, source_table, source_pk)
                DO UPDATE SET
                    external_code = EXCLUDED.external_code,
                    name = EXCLUDED.name,
                    category = EXCLUDED.category,
                    active = TRUE,
                    deleted_at = NULL,
                    last_seen_at = CURRENT_TIMESTAMP(6),
                    row_version = CASE
                        WHEN COALESCE(asset.source_hash, '')
                                     <> EXCLUDED.source_hash
                          OR asset.active = FALSE
                        THEN asset.row_version + 1
                        ELSE asset.row_version
                    END,
                    source_hash = EXCLUDED.source_hash
                """;

        for (ZeladoriaSourceAdapter.AtivoZeladoriaRecord sourceAsset
                : snapshot.assets()) {
            String sourcePk = sourceAsset.id();
            presentSourceIds.add(sourcePk);
            String prefixo = sourceAsset.prefixo();
            String tipo = sourceAsset.tipo();
            String modelo = sourceAsset.modelo();
            String name = normalizeName(modelo, prefixo);
            String sourceHash = hashRow(sourcePk, prefixo, tipo, modelo);
            ExistingAsset existing = existingAssets.get(sourcePk);
            String assetId = existing == null
                    ? stableAssetId(sourcePk)
                    : existing.id();
            boolean inserted = existing == null;
            boolean updated = existing != null
                    && (!Objects.equals(existing.sourceHash(), sourceHash)
                    || !existing.active());

            if (inserted) {
                recordsInserted++;
            } else if (updated) {
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
            registrarAtivoNaMemoria(
                    syncRunId,
                    assetId,
                    sourcePk,
                    prefixo,
                    tipo,
                    modelo,
                    name,
                    sourceHash,
                    inserted,
                    updated
            );
        }

        for (Map.Entry<String, ExistingAsset> entry
                : existingAssets.entrySet()) {
            ExistingAsset existing = entry.getValue();
            if (!existing.active()
                    || presentSourceIds.contains(entry.getKey())) {
                continue;
            }
            int changed = cortexJdbcTemplate.update("""
                    UPDATE asset
                    SET active = FALSE,
                        deleted_at = COALESCE(
                                deleted_at,
                                CURRENT_TIMESTAMP(6)
                        ),
                        row_version = row_version + 1
                    WHERE id = ?
                      AND active = TRUE
                    """, existing.id());
            if (changed == 1) {
                recordsDeactivated++;
                registrarAtivoDesativadoNaMemoria(
                        syncRunId,
                        existing,
                        entry.getKey()
                );
            }
        }

        finishSyncRunSuccess(
                syncRunId,
                snapshot.assets().size(),
                recordsInserted,
                recordsUpdated,
                recordsDeactivated
        );
        upsertCheckpointSuccess();
        registrarImportacaoNaMemoria(
                syncRunId,
                "SUCCESS",
                snapshot.assets().size(),
                recordsInserted,
                recordsUpdated,
                recordsDeactivated,
                null
        );
        return new ImportApplicationResult(
                recordsInserted,
                recordsUpdated,
                recordsDeactivated
        );
    }

    private Map<String, ExistingAsset> loadExistingAssets() {
        Map<String, ExistingAsset> result = new HashMap<>();
        List<ExistingSourceAsset> rows = cortexJdbcTemplate.query(
                """
                SELECT
                    id,
                    source_pk,
                    source_hash,
                    active,
                    external_code,
                    name,
                    category
                FROM asset
                WHERE source_database = ?
                  AND source_table = ?
                """,
                (resultSet, rowNumber) -> new ExistingSourceAsset(
                        resultSet.getString("source_pk"),
                        new ExistingAsset(
                                resultSet.getString("id"),
                                resultSet.getString("source_hash"),
                                resultSet.getBoolean("active"),
                                resultSet.getString("external_code"),
                                resultSet.getString("name"),
                                resultSet.getString("category")
                        )
                ),
                SOURCE_DATABASE,
                SOURCE_TABLE
        );
        rows.forEach(row -> result.put(row.sourcePk(), row.asset()));
        return result;
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
                ON CONFLICT (connector_name, source_database, source_table) DO UPDATE SET
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
                    ON CONFLICT (connector_name, source_database, source_table) DO UPDATE SET
                        last_error_at = CURRENT_TIMESTAMP(6),
                        last_error_message = EXCLUDED.last_error_message
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

    private void registrarAtivoNaMemoria(
            String syncRunId,
            String assetId,
            String sourcePk,
            String prefixo,
            String tipo,
            String modelo,
            String name,
            String sourceHash,
            boolean inserted,
            boolean updated
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("legacySystem", SOURCE_DATABASE);
        metadata.put("legacyTable", SOURCE_TABLE);
        metadata.put("legacyId", sourcePk);
        metadata.put("importBatchId", syncRunId);
        metadata.put("sourceHash", sourceHash);

        memoryService.registrarObjeto(
                "ATIVO",
                assetId,
                prefixo,
                name,
                "ATIVO",
                "IMPORTACAO_LEGADO",
                "asset",
                metadata
        );

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("prefixo", prefixo);
        fields.put("tipo", tipo);
        fields.put("modelo", modelo);
        fields.put("nome", name);
        fields.put("source_database", SOURCE_DATABASE);
        fields.put("source_table", SOURCE_TABLE);
        fields.put("source_pk", sourcePk);
        fields.put("source_hash", sourceHash);

        memoryService.registrarEvidencias(
                "ATIVO",
                assetId,
                "IMPORTACAO_LEGADO",
                fields
        );

        memoryService.registrarMapeamentoLegado(
                "ATIVO",
                assetId,
                SOURCE_DATABASE,
                SOURCE_TABLE,
                sourcePk,
                sourceHash,
                syncRunId,
                fields
        );

        if (!inserted && !updated) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>(metadata);
        payload.put("schemaVersion", 1);
        payload.put("assetId", assetId);
        payload.put("prefixo", prefixo);
        payload.put("tipo", tipo);
        payload.put("modelo", modelo);
        payload.put("nome", name);

        memoryService.registrarEvento(
                "ATIVO",
                assetId,
                inserted
                        ? "ATIVO_IMPORTADO_DO_LEGADO"
                        : "ATIVO_ATUALIZADO_DO_LEGADO",
                "IMPORTACAO_LEGADO",
                payload
        );
    }

    private void registrarAtivoDesativadoNaMemoria(
            String syncRunId,
            ExistingAsset existing,
            String sourcePk
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("legacySystem", SOURCE_DATABASE);
        metadata.put("legacyTable", SOURCE_TABLE);
        metadata.put("legacyId", sourcePk);
        metadata.put("importBatchId", syncRunId);
        metadata.put("sourceHash", existing.sourceHash());
        metadata.put("active", false);

        memoryService.registrarObjeto(
                "ATIVO",
                existing.id(),
                existing.externalCode(),
                existing.name(),
                "INATIVO",
                "IMPORTACAO_LEGADO",
                "asset",
                metadata
        );
        memoryService.registrarEvidencias(
                "ATIVO",
                existing.id(),
                "IMPORTACAO_LEGADO",
                Map.of(
                        "active", false,
                        "source_database", SOURCE_DATABASE,
                        "source_table", SOURCE_TABLE,
                        "source_pk", sourcePk
                )
        );
        memoryService.registrarEvento(
                "ATIVO",
                existing.id(),
                "ATIVO_EXCLUIDO_DA_ORIGEM",
                "IMPORTACAO_LEGADO",
                metadata
        );
    }

    private void registrarImportacaoNaMemoria(
            String syncRunId,
            String status,
            int recordsRead,
            int recordsInserted,
            int recordsUpdated,
            int recordsDeactivated,
            String errorMessage
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("connectorName", CONNECTOR_NAME);
        payload.put("sourceDatabase", SOURCE_DATABASE);
        payload.put("sourceTable", SOURCE_TABLE);
        payload.put("recordsRead", recordsRead);
        payload.put("recordsInserted", recordsInserted);
        payload.put("recordsUpdated", recordsUpdated);
        payload.put("recordsDeactivated", recordsDeactivated);
        payload.put("errorMessage", errorMessage);

        memoryService.registrarObjeto(
                "IMPORTACAO_LEGADA",
                syncRunId,
                CONNECTOR_NAME + ":" + SOURCE_TABLE,
                "Importação " + CONNECTOR_NAME,
                status,
                "IMPORTACAO_LEGADO",
                "source_sync_run",
                payload
        );

        memoryService.registrarEvento(
                "IMPORTACAO_LEGADA",
                syncRunId,
                "SUCCESS".equals(status)
                        ? "IMPORTACAO_LEGADA_CONCLUIDA"
                        : "IMPORTACAO_LEGADA_FALHOU",
                "IMPORTACAO_LEGADO",
                payload
        );
    }

    private String stableAssetId(String sourcePk) {
        return UUID.nameUUIDFromBytes((SOURCE_DATABASE + "." + SOURCE_TABLE + ":" + sourcePk).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizeName(String modelo, String prefixo) {
        if (!isBlank(modelo)) return modelo.trim();
        if (!isBlank(prefixo)) return prefixo.trim();
        return "Ativo sem modelo";
    }

    private String hashRow(
            String sourcePk,
            String prefixo,
            String tipo,
            String modelo
    ) {
        try {
            String raw = nullToEmpty(sourcePk)
                    + "|" + nullToEmpty(prefixo)
                    + "|" + nullToEmpty(tipo)
                    + "|" + nullToEmpty(modelo);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ignored) {
            throw new IllegalStateException(APPLY_FAILURE_MESSAGE);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void executeRequiresNew(Runnable action) {
        requiresNewTransactions.execute(status -> {
            action.run();
            return null;
        });
    }

    private void executeRequiresNewBestEffort(Runnable action) {
        try {
            executeRequiresNew(action);
        } catch (Exception ignored) {
            // A diagnostic failure cannot expose or replace the safe error.
        }
    }

    private RuntimeException publicImportFailure() {
        return new IllegalStateException(PUBLIC_FAILURE_MESSAGE);
    }

    private SnapshotValidationException snapshotConflict(String detail) {
        return new SnapshotValidationException(
                "Snapshot Zeladoria invalido: " + detail + "."
        );
    }

    private static TransactionTemplate transactionTemplateFor(
            JdbcTemplate jdbcTemplate
    ) {
        return requiresNewTransactionTemplate(
                new DataSourceTransactionManager(
                        Objects.requireNonNull(
                                jdbcTemplate.getDataSource(),
                                "JdbcTemplate sem DataSource transacional"
                        )
                )
        );
    }

    private static TransactionTemplate requiresNewTransactionTemplate(
            PlatformTransactionManager transactionManager
    ) {
        TransactionTemplate transactions = new TransactionTemplate(
                transactionManager
        );
        transactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        return transactions;
    }

    private static SourceImportRunLock postgresqlRunLock(
            JdbcTemplate jdbcTemplate
    ) {
        return SourceImportRunLock.postgresql(
                Objects.requireNonNull(
                        jdbcTemplate.getDataSource(),
                        "JdbcTemplate sem DataSource para lock Zeladoria"
                ),
                CONNECTOR_NAME,
                LOCK_FAILURE_MESSAGE
        );
    }

    private static final class SnapshotValidationException
            extends IllegalStateException {

        private SnapshotValidationException(String message) {
            super(message);
        }
    }

    private record PreparedSnapshot(
            List<ZeladoriaSourceAdapter.AtivoZeladoriaRecord> assets
    ) {

        private PreparedSnapshot {
            assets = List.copyOf(assets);
        }
    }

    private record ExistingAsset(
            String id,
            String sourceHash,
            boolean active,
            String externalCode,
            String name,
            String category
    ) {
    }

    private record ExistingSourceAsset(
            String sourcePk,
            ExistingAsset asset
    ) {
    }

    private record ImportApplicationResult(
            int recordsInserted,
            int recordsUpdated,
            int recordsDeactivated
    ) {
    }
}
