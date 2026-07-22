package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CanonicalMutationTraceMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V43__canonical_offline_mutation_trace.sql"
    );

    @Test
    void addsOnlyMissingCanonicalTraceColumnsAndExpandsResults() throws Exception {
        assertThat(Files.exists(MIGRATION))
                .as("V43 canonical offline mutation trace migration")
                .isTrue();

        String sql = Files.readString(MIGRATION);

        assertThat(sql)
                .contains("escopo_autorizacao_json JSON")
                .contains("field_patch_json JSON")
                .contains("base_values_json JSON")
                .contains("evento_cliente_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("causacao_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin")
                .contains("dependencias_json JSON")
                .contains("idx_sync_mutacao_owner_client_event")
                .contains("proprietario_id, evento_cliente_id")
                .contains("'CONFLITO'")
                .contains("'REJEITADA'")
                .contains("'CONCILIADA'")
                .doesNotContain("ADD COLUMN proprietario_id")
                .doesNotContain("ADD COLUMN correlacao_id")
                .doesNotContain("ADD COLUMN payload_hash");
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "CORTEX_MYSQL_ROOT_PASSWORD",
            matches = ".+"
    )
    void buildsSchemaThroughV42BeforeApplyingCanonicalTraceMigration() throws Exception {
        String database = "cortex_trace_" + UUID.randomUUID().toString().replace("-", "");
        String rootUrl = "jdbc:mysql://127.0.0.1:3307/"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String databaseUrl = "jdbc:mysql://127.0.0.1:3307/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        String password = System.getenv("CORTEX_MYSQL_ROOT_PASSWORD");

        try (Connection connection = DriverManager.getConnection(rootUrl, "root", password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + database
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        try {
            Flyway.configure()
                    .dataSource(databaseUrl, "root", password)
                    .locations("filesystem:src/main/resources/db/migration")
                    .target(MigrationVersion.fromVersion("42"))
                    .load()
                    .migrate();
            Flyway.configure()
                    .dataSource(databaseUrl, "root", password)
                    .locations("filesystem:src/main/resources/db/migration")
                    .load()
                    .migrate();

            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    databaseUrl,
                    "root",
                    password
            );
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            assertTraceColumn(jdbc, database, "evento_cliente_id", "char", 36L, "ascii_bin");
            assertTraceColumn(jdbc, database, "causacao_id", "varchar", 120L, "ascii_bin");
            assertJsonColumn(jdbc, database, "escopo_autorizacao_json");
            assertJsonColumn(jdbc, database, "field_patch_json");
            assertJsonColumn(jdbc, database, "base_values_json");
            assertJsonColumn(jdbc, database, "dependencias_json");
            assertTraceColumn(jdbc, database, "payload_hash", "char", 64L, "ascii_bin");

            assertThat(checkClause(jdbc, database, "chk_sync_mutacao_status"))
                    .contains("CONFLITO", "REJEITADA");
            assertThat(checkClause(jdbc, database, "chk_cortex_evento_resultado"))
                    .contains("CONFLITO", "REJEITADA", "CONCILIADA");
            assertThat(jdbc.queryForList(
                    """
                    SELECT column_name
                    FROM information_schema.statistics
                    WHERE table_schema = ?
                      AND table_name = 'sync_mutacao_cliente'
                      AND index_name = 'idx_sync_mutacao_owner_client_event'
                    ORDER BY seq_in_index
                    """,
                    String.class,
                    database
            )).containsExactly("proprietario_id", "evento_cliente_id");
        } finally {
            try (Connection connection = DriverManager.getConnection(rootUrl, "root", password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    private void assertJsonColumn(JdbcTemplate jdbc, String database, String column) {
        Map<String, Object> metadata = columnMetadata(jdbc, database, column);
        assertThat(metadata.get("DATA_TYPE")).isEqualTo("json");
    }

    private void assertTraceColumn(
            JdbcTemplate jdbc,
            String database,
            String column,
            String dataType,
            long length,
            String collation
    ) {
        Map<String, Object> metadata = columnMetadata(jdbc, database, column);
        assertThat(metadata.get("DATA_TYPE")).isEqualTo(dataType);
        assertThat(((Number) metadata.get("CHARACTER_MAXIMUM_LENGTH")).longValue())
                .isEqualTo(length);
        assertThat(metadata.get("COLLATION_NAME")).isEqualTo(collation);
    }

    private Map<String, Object> columnMetadata(
            JdbcTemplate jdbc,
            String database,
            String column
    ) {
        return jdbc.queryForMap(
                """
                SELECT data_type, character_maximum_length, collation_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'sync_mutacao_cliente'
                  AND column_name = ?
                """,
                database,
                column
        );
    }

    private String checkClause(JdbcTemplate jdbc, String database, String constraint) {
        return jdbc.queryForObject(
                """
                SELECT check_clause
                FROM information_schema.check_constraints
                WHERE constraint_schema = ?
                  AND constraint_name = ?
                """,
                String.class,
                database,
                constraint
        );
    }
}
