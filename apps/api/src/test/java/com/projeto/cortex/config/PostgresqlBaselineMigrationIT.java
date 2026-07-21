package com.projeto.cortex.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlBaselineMigrationIT {

    private static final String REQUIRED_TABLES_RESOURCE =
            "/postgresql/v44-required-tables.txt";

    @Test
    void migratesCleanPostgresql18DatabaseToExactV44Schema() throws Exception {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")) {
            database.start();

            Flyway flyway = Flyway.configure()
                    .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                    .locations("classpath:db/migration-postgresql")
                    .load();

            MigrateResult migration = flyway.migrate();
            assertThat(migration.success).isTrue();

            try (Connection connection = DriverManager.getConnection(
                    database.getJdbcUrl(),
                    database.getUsername(),
                    database.getPassword()
            )) {
                assertFlywayAppliedExactlyV44(connection);
                assertFinalTableInventory(connection);
                assertStructuralCommitSequenceStartsAtZero(connection);
                assertFinalPostgresqlInvariants(connection);
                assertEveryForeignKeyHasLeadingSupportingIndex(connection);
                assertNoDuplicateForeignKeyEdges(connection);
            }
        }
    }

    private static void assertFlywayAppliedExactlyV44(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT version, success
                     FROM flyway_schema_history
                     WHERE type = 'SQL'
                     ORDER BY installed_rank
                     """)) {
            assertThat(rows.next()).as("Flyway must record the V44 SQL baseline").isTrue();
            assertThat(rows.getString("version")).isEqualTo("44");
            assertThat(rows.getBoolean("success")).isTrue();
            assertThat(rows.next()).as("Flyway must apply only the single V44 baseline").isFalse();
        }
    }

    private static void assertFinalTableInventory(Connection connection) throws Exception {
        Set<String> expectedTables = requiredTables();
        Set<String> actualTables = new TreeSet<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT table_name
                     FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_type = 'BASE TABLE'
                       AND table_name <> 'flyway_schema_history'
                     ORDER BY table_name
                     """)) {
            while (rows.next()) {
                actualTables.add(rows.getString("table_name"));
            }
        }

        assertThat(expectedTables).hasSize(116);
        assertThat(expectedTables)
                .contains(
                        "pdor_snapshot",
                        "tarefa",
                        "obra_geometria",
                        "auth_capacidade_administrativa",
                        "cortex_evento_operacional",
                        "sync_mutacao_cliente"
                )
                .doesNotContain("pdoc_snapshot");
        assertThat(actualTables).containsExactlyElementsOf(expectedTables);
    }

    private static void assertStructuralCommitSequenceStartsAtZero(Connection connection)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT id, ultima_commit_seq
                     FROM cortex_evento_commit_sequence
                     """)) {
            assertThat(rows.next()).as("structural commit sequence row must exist").isTrue();
            assertThat(rows.getInt("id")).isEqualTo(1);
            assertThat(rows.getLong("ultima_commit_seq")).isZero();
            assertThat(rows.next()).as("commit sequence control table must be a singleton").isFalse();
        }
    }

    private static void assertFinalPostgresqlInvariants(Connection connection) throws SQLException {
        assertThat(indexDefinition(connection, "uq_auth_identity_email_normalized"))
                .contains("lower")
                .contains("email_autenticacao")
                .contains("WHERE");
        assertThat(indexDefinition(connection, "gin_mensagem_corpo_portuguese"))
                .contains("USING gin")
                .contains("to_tsvector('portuguese'");

        assertThat(columnType(connection, "auth_capacidade_administrativa", "ativa"))
                .isEqualTo("boolean");
        assertThat(constraintDefinition(connection, "auth_capacidade_administrativa", "chk_auth_capacidade_nome"))
                .contains("ADMINISTRAR_PAPEIS");
        assertThat(constraintDefinition(connection, "finance_rateio", "chk_fin_rateio_origem_unica"))
                .contains("num_nonnulls");
        assertThat(constraintDefinition(connection, "finance_cobranca_email", "chk_fin_cobranca_email_alvo"))
                .contains("num_nonnulls");
        assertThat(constraintDefinition(connection, "tarefa", "uq_tarefa_criador_mutacao"))
                .contains("criada_por_colaborador_id")
                .contains("criada_mutacao_id");

        assertThat(foreignKeyCount(connection, "auth_session", "colaborador"))
                .isPositive();
        assertThat(foreignKeyCount(connection, "stored_object", "colaborador"))
                .isPositive();
        assertThat(foreignKeyCount(connection, "finance_lancamento", "obra"))
                .isPositive();
    }

    private static String indexDefinition(Connection connection, String indexName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = ?
                """)) {
            statement.setString(1, indexName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("index %s", indexName).isTrue();
                return rows.getString("indexdef");
            }
        }
    }

    private static String columnType(Connection connection, String tableName, String columnName)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("column %s.%s", tableName, columnName).isTrue();
                return rows.getString("data_type");
            }
        }
    }

    private static String constraintDefinition(
            Connection connection, String tableName, String constraintName
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT pg_get_constraintdef(c.oid) AS definition
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public' AND t.relname = ? AND c.conname = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, constraintName);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("constraint %s.%s", tableName, constraintName).isTrue();
                return rows.getString("definition");
            }
        }
    }

    private static int foreignKeyCount(Connection connection, String tableName, String referencedTable)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT count(*)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON ccu.constraint_name = tc.constraint_name
                 AND ccu.table_schema = tc.table_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY'
                  AND ccu.table_name = ?
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, referencedTable);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getInt(1);
            }
        }
    }

    private static void assertEveryForeignKeyHasLeadingSupportingIndex(Connection connection)
            throws SQLException {
        List<String> uncovered = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT child.relname AS child_table,
                            fk.conname AS foreign_key,
                            string_agg(attribute.attname, ', ' ORDER BY key_column.ordinality) AS columns
                     FROM pg_constraint fk
                     JOIN pg_class child ON child.oid = fk.conrelid
                     JOIN pg_namespace namespace ON namespace.oid = child.relnamespace
                     JOIN unnest(fk.conkey) WITH ORDINALITY AS key_column(attnum, ordinality)
                       ON true
                     JOIN pg_attribute attribute
                       ON attribute.attrelid = child.oid AND attribute.attnum = key_column.attnum
                     WHERE fk.contype = 'f'
                       AND namespace.nspname = 'public'
                       AND NOT EXISTS (
                           SELECT 1
                           FROM pg_index candidate
                           WHERE candidate.indrelid = fk.conrelid
                             AND candidate.indisvalid
                             AND candidate.indpred IS NULL
                             AND NOT EXISTS (
                                 SELECT 1
                                 FROM unnest(fk.conkey) WITH ORDINALITY AS fk_column(attnum, ordinality)
                                 LEFT JOIN LATERAL (
                                     SELECT index_column.attnum
                                     FROM unnest(candidate.indkey::smallint[])
                                              WITH ORDINALITY AS index_column(attnum, ordinality)
                                     WHERE index_column.ordinality = fk_column.ordinality
                                       AND index_column.ordinality <= candidate.indnkeyatts
                                 ) leading_index_column ON true
                                 WHERE leading_index_column.attnum
                                       IS DISTINCT FROM fk_column.attnum
                             )
                       )
                     GROUP BY child.relname, fk.conname
                     ORDER BY child.relname, fk.conname
                     """)) {
            while (rows.next()) {
                uncovered.add(rows.getString("child_table") + "."
                        + rows.getString("foreign_key") + " ("
                        + rows.getString("columns") + ")");
            }
        }

        assertThat(uncovered)
                .as("every child foreign key needs a valid, non-partial, leading PostgreSQL index")
                .isEmpty();
    }

    private static void assertNoDuplicateForeignKeyEdges(Connection connection) throws SQLException {
        List<String> duplicateEdges = new ArrayList<>();

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT child.relname AS child_table,
                            string_agg(fk.conname, ', ' ORDER BY fk.conname) AS constraints
                     FROM pg_constraint fk
                     JOIN pg_class child ON child.oid = fk.conrelid
                     JOIN pg_namespace namespace ON namespace.oid = child.relnamespace
                     WHERE fk.contype = 'f'
                       AND namespace.nspname = 'public'
                     GROUP BY child.relname, fk.conkey, fk.confrelid, fk.confkey
                     HAVING count(*) > 1
                     ORDER BY child.relname
                     """)) {
            while (rows.next()) {
                duplicateEdges.add(rows.getString("child_table") + ": "
                        + rows.getString("constraints"));
            }
        }

        assertThat(duplicateEdges)
                .as("the clean baseline must not retain duplicate foreign-key edges")
                .isEmpty();
    }

    private static Set<String> requiredTables() throws IOException {
        InputStream resource = PostgresqlBaselineMigrationIT.class.getResourceAsStream(
                REQUIRED_TABLES_RESOURCE
        );
        assertThat(resource)
                .as("checked-in V44 table inventory %s", REQUIRED_TABLES_RESOURCE)
                .isNotNull();

        try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                resource,
                StandardCharsets.UTF_8
        ))) {
            Set<String> tables = new TreeSet<>();
            lines.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .forEach(tables::add);
            return tables;
        }
    }
}
