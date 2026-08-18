package com.projeto.cortex.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.integracoes.ZeladoriaAssetSnapshot;
import com.projeto.cortex.integracoes.ZeladoriaSourceAdapter;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlZeladoriaImportAtomicityIT {

    private static final String SOURCE_DATABASE = "dbsta" + "vias_zld";

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("zeladoria_import_it");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM cortex_evidencia_operacional");
        jdbc.update("DELETE FROM cortex_mapeamento_legado");
        jdbc.update("DELETE FROM cortex_estado_entidade");
        jdbc.update("DELETE FROM cortex_evento_operacional");
        jdbc.update("DELETE FROM cortex_objeto");
        jdbc.update("DELETE FROM asset_alias");
        jdbc.update("DELETE FROM asset");
        jdbc.update("""
                DELETE FROM source_sync_checkpoint
                WHERE connector_name = 'zld_asset_import'
                """);
        jdbc.update("""
                DELETE FROM source_sync_run
                WHERE connector_name = 'zld_asset_import'
                """);
    }

    @Test
    void completeCurrentSnapshotSoftDeactivatesMissingAndInsertsPresent() {
        seedAsset("asset-old", "1", true, null, 7L);
        ZeladoriaSourceAdapter source = source(
                ZeladoriaAssetSnapshot.complete(List.of(
                        asset("2", "EQ-02", "ROLO", "Modelo B")
                ))
        );

        AssetImportResult result = service(source).importFromZldAtivos();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.recordsRead()).isOne();
        assertThat(result.recordsInserted()).isOne();
        assertThat(result.recordsDeactivated()).isOne();
        Map<String, Object> missing = jdbc.queryForMap("""
                SELECT id, active, deleted_at, row_version
                FROM asset
                WHERE source_database = ?
                  AND source_table = 'ativos'
                  AND source_pk = '1'
                """, SOURCE_DATABASE);
        assertThat(missing)
                .containsEntry("id", "asset-old")
                .containsEntry("active", false)
                .containsEntry("row_version", 8L);
        assertThat(missing.get("deleted_at")).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM asset
                WHERE source_database = ?
                  AND source_table = 'ativos'
                  AND source_pk = '2'
                  AND active = TRUE
                  AND deleted_at IS NULL
                """, Integer.class, SOURCE_DATABASE)).isOne();
    }

    @Test
    void completeSnapshotReactivatesTheSameStableAssetRow() {
        seedAsset("asset-stable", "1", false, "CURRENT_TIMESTAMP", 4L);
        ZeladoriaSourceAdapter source = source(
                ZeladoriaAssetSnapshot.complete(List.of(
                        asset("1", "EQ-01", "CAMINHAO", "Modelo Atual")
                ))
        );

        AssetImportResult result = service(source).importFromZldAtivos();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForMap("""
                SELECT id, active, deleted_at
                FROM asset
                WHERE source_pk = '1'
                  AND source_database = ?
                  AND source_table = 'ativos'
                """, SOURCE_DATABASE))
                .containsEntry("id", "asset-stable")
                .containsEntry("active", true)
                .containsEntry("deleted_at", null);
    }

    @Test
    void incompleteOrAmbiguousSnapshotMakesNoDomainChange() {
        seedAsset("asset-old", "1", true, null, 2L);
        ZeladoriaSourceAdapter incomplete = source(
                new ZeladoriaAssetSnapshot(List.of(), false)
        );

        assertThatThrownBy(() -> service(incomplete).importFromZldAtivos())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Falha ao importar ativos da Zeladoria.")
                .hasNoCause();
        assertUnchangedExistingAsset();

        ZeladoriaSourceAdapter duplicate = source(
                ZeladoriaAssetSnapshot.complete(List.of(
                        asset("2", "EQ-02", "ROLO", "Modelo B"),
                        asset("2", "EQ-99", "TRATOR", "Modelo C")
                ))
        );
        assertThatThrownBy(() -> service(duplicate).importFromZldAtivos())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Falha ao importar ativos da Zeladoria.")
                .hasNoCause();
        assertUnchangedExistingAsset();
    }

    @Test
    void lateApplyFailureRollsBackEveryDomainWrite() {
        ZeladoriaSourceAdapter source = source(
                ZeladoriaAssetSnapshot.complete(List.of(
                        asset("1", "EQ-01", "CAMINHAO", "Modelo A"),
                        asset("2", "EQ-02", "ROLO", "Modelo B")
                ))
        );
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        doThrow(new IllegalStateException("internal persistence detail"))
                .when(memory)
                .registrarObjeto(
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any()
                );

        assertThatThrownBy(() -> service(source, memory).importFromZldAtivos())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Falha ao importar ativos da Zeladoria.")
                .hasNoCause();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM asset",
                Integer.class
        )).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT status, error_message
                FROM source_sync_run
                WHERE connector_name = 'zld_asset_import'
                ORDER BY started_at DESC
                LIMIT 1
                """))
                .containsEntry("status", "FAILED")
                .containsEntry(
                        "error_message",
                        "Falha ao aplicar snapshot da Zeladoria."
                );
        assertThat(jdbc.queryForMap("""
                SELECT last_success_at, last_error_message
                FROM source_sync_checkpoint
                WHERE connector_name = 'zld_asset_import'
                """))
                .containsEntry("last_success_at", null)
                .containsEntry(
                        "last_error_message",
                        "Falha ao aplicar snapshot da Zeladoria."
                );
    }

    private void assertUnchangedExistingAsset() {
        assertThat(jdbc.queryForMap("""
                SELECT active, deleted_at, row_version
                FROM asset
                WHERE id = 'asset-old'
                """))
                .containsEntry("active", true)
                .containsEntry("deleted_at", null)
                .containsEntry("row_version", 2L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM asset",
                Integer.class
        )).isOne();
    }

    private AssetImportService service(ZeladoriaSourceAdapter source) {
        return service(
                source,
                mock(CortexOperationalMemoryService.class)
        );
    }

    private AssetImportService service(
            ZeladoriaSourceAdapter source,
            CortexOperationalMemoryService memory
    ) {
        return new AssetImportService(
                jdbc,
                source,
                memory
        );
    }

    private ZeladoriaSourceAdapter source(ZeladoriaAssetSnapshot snapshot) {
        ZeladoriaSourceAdapter source = mock(ZeladoriaSourceAdapter.class);
        when(source.fetchCompleteSnapshot(anyInt())).thenReturn(snapshot);
        when(source.fetchAssets(anyInt())).thenReturn(snapshot.assets());
        return source;
    }

    private ZeladoriaSourceAdapter.AtivoZeladoriaRecord asset(
            String id,
            String prefixo,
            String tipo,
            String modelo
    ) {
        return new ZeladoriaSourceAdapter.AtivoZeladoriaRecord(
                id,
                prefixo,
                tipo,
                modelo
        );
    }

    private void seedAsset(
            String id,
            String sourcePk,
            boolean active,
            String deletedAtExpression,
            long rowVersion
    ) {
        String deletedAt = deletedAtExpression == null
                ? "NULL"
                : deletedAtExpression;
        jdbc.update("""
                INSERT INTO asset (
                    id, source_database, source_table, source_pk,
                    external_code, name, category, active,
                    source_hash, deleted_at, row_version
                ) VALUES (
                    ?, ?, 'ativos', ?,
                    'EQ-OLD', 'Modelo antigo', 'CAMINHAO', ?,
                    repeat('a', 64), %s, ?
                )
                """.formatted(deletedAt), id, SOURCE_DATABASE, sourcePk, active, rowVersion);
    }
}
