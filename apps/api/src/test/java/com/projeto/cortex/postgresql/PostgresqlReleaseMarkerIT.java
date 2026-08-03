package com.projeto.cortex.postgresql;

import com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresqlReleaseMarkerIT {

    private static final String FIRST_REVISION = "a".repeat(40);
    private static final String SECOND_REVISION = "b".repeat(40);
    private static final String CORRUPTED_REVISION = "c".repeat(40);
    private static final String FIRST_MARKER = releaseMarker(FIRST_REVISION);
    private static final String SECOND_MARKER = releaseMarker(SECOND_REVISION);
    private static final String CORRUPTED_EXPECTED_MARKER =
            releaseMarker(CORRUPTED_REVISION);
    private static final String FOREIGN_MARKER = releaseMarker("d".repeat(40));

    @Test
    void migrationLauncherWritesOnlyExplicitCanonicalEvidenceAfterFlyway() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("StaviasCortex")) {
            database.start();

            assertThatThrownBy(() -> runMigration(
                    database,
                    true,
                    "short-sha",
                    FIRST_MARKER
            ))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining("jdbc:postgresql")
                    .hasMessageNotContaining(database.getPassword());
            assertThatThrownBy(() -> runMigration(
                    database,
                    true,
                    FIRST_REVISION,
                    "not canonical"
            ))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining("jdbc:postgresql")
                    .hasMessageNotContaining(database.getPassword());

            runMigration(database, true, FIRST_REVISION, FIRST_MARKER);
            JdbcTemplate jdbcTemplate = jdbcTemplate(database);
            assertThat(jdbcTemplate.queryForMap("""
                    SELECT revision, marker, created_at
                    FROM cortex_release_marker
                    WHERE revision = ?
                    """, FIRST_REVISION))
                    .containsEntry("revision", FIRST_REVISION)
                    .containsEntry("marker", FIRST_MARKER)
                    .containsKey("created_at");

            runMigration(database, true, FIRST_REVISION, FIRST_MARKER);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cortex_release_marker",
                    Integer.class
            )).isEqualTo(1);

            assertThatThrownBy(() -> runMigration(
                    database,
                    true,
                    FIRST_REVISION,
                    SECOND_MARKER
            ))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining(FIRST_REVISION)
                    .hasMessageNotContaining(SECOND_MARKER);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT marker FROM cortex_release_marker WHERE revision = ?",
                    String.class,
                    FIRST_REVISION
            )).isEqualTo(FIRST_MARKER);

            runMigration(database, true, SECOND_REVISION, SECOND_MARKER);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cortex_release_marker",
                    Integer.class
            )).isEqualTo(2);
            assertThat(jdbcTemplate.queryForMap("""
                    SELECT revision, marker
                    FROM cortex_release_marker
                    WHERE revision = ?
                    """, SECOND_REVISION))
                    .containsEntry("revision", SECOND_REVISION)
                    .containsEntry("marker", SECOND_MARKER);

            jdbcTemplate.update(
                    "INSERT INTO cortex_release_marker (revision, marker) VALUES (?, ?)",
                    CORRUPTED_REVISION,
                    FOREIGN_MARKER
            );
            assertThatThrownBy(() -> runMigration(
                    database,
                    true,
                    CORRUPTED_REVISION,
                    CORRUPTED_EXPECTED_MARKER
            ))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageNotContaining(CORRUPTED_REVISION)
                    .hasMessageNotContaining(FOREIGN_MARKER);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT marker FROM cortex_release_marker WHERE revision = ?",
                    String.class,
                    CORRUPTED_REVISION
            )).isEqualTo(FOREIGN_MARKER);

            assertDatabaseConstraintsRejectNonCanonicalEvidence(jdbcTemplate);
            assertRuntimeRoleIsSelectOnly(database, jdbcTemplate);

            jdbcTemplate.update("DELETE FROM cortex_release_marker");
            runMigration(database, false, null, null);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM cortex_release_marker",
                    Integer.class
            )).isZero();
        }
    }

    @Test
    void migrationPinsTheCanonicalPublicSchemaWhenTheRoleDefaultsToPgCatalog() {
        try (PostgreSQLContainer<?> database = new PostgreSQLContainer<>("postgres:18")
                .withDatabaseName("Sta" + "viasCortex")) {
            database.start();

            String migratorPassword = "fixture-migrator-password";
            String databaseName = database.getDatabaseName();
            JdbcTemplate ownerJdbc = jdbcTemplate(database);
            ownerJdbc.execute(
                    "CREATE ROLE cortex_migrator LOGIN PASSWORD '" + migratorPassword + "'"
            );
            ownerJdbc.execute("ALTER SCHEMA public OWNER TO cortex_migrator");
            ownerJdbc.execute("""
                    GRANT CONNECT, CREATE
                    ON DATABASE "%s"
                    TO cortex_migrator
                    """.formatted(databaseName));
            ownerJdbc.execute("""
                    ALTER ROLE cortex_migrator
                    IN DATABASE "%s"
                    SET search_path = pg_catalog
                    """.formatted(databaseName));

            JdbcTemplate migratorJdbc = new JdbcTemplate(new DriverManagerDataSource(
                    database.getJdbcUrl(),
                    "cortex_migrator",
                    migratorPassword
            ));
            assertThat(migratorJdbc.queryForObject(
                    "SELECT current_schema()",
                    String.class
            )).isEqualTo("pg_catalog");

            runMigration(
                    database,
                    "cortex_migrator",
                    migratorPassword,
                    true,
                    FIRST_REVISION,
                    FIRST_MARKER
            );

            assertThat(ownerJdbc.queryForObject(
                    "SELECT to_regclass('public.flyway_schema_history')::text",
                    String.class
            )).isEqualTo("flyway_schema_history");
            assertThat(ownerJdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_catalog.pg_tables
                    WHERE schemaname = 'pg_catalog'
                      AND tablename = 'flyway_schema_history'
                    """, Integer.class)).isZero();
            assertThat(ownerJdbc.queryForObject(
                    "SELECT MAX(version) FROM public.flyway_schema_history WHERE success",
                    String.class
            )).isEqualTo("66");
            assertThat(ownerJdbc.queryForObject(
                    """
                    SELECT marker
                    FROM public.cortex_release_marker
                    WHERE revision = ?
                    """,
                    String.class,
                    FIRST_REVISION
            )).isEqualTo(FIRST_MARKER);
        }
    }

    private void assertDatabaseConstraintsRejectNonCanonicalEvidence(
            JdbcTemplate jdbcTemplate
    ) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO cortex_release_marker (revision, marker) VALUES (?, ?)",
                "A".repeat(40),
                releaseMarker("g".repeat(40))
        )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO cortex_release_marker (revision, marker) VALUES (?, ?)",
                "e".repeat(40),
                "B".repeat(43)
        )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO cortex_release_marker (revision, marker) VALUES (?, ?)",
                "f".repeat(40),
                FIRST_MARKER + "="
        )).isInstanceOf(RuntimeException.class);
    }

    private void assertRuntimeRoleIsSelectOnly(
            PostgreSQLContainer<?> database,
            JdbcTemplate ownerJdbc
    ) {
        String password = "fixture-runtime-password";
        ownerJdbc.execute("CREATE ROLE cortex_runtime LOGIN PASSWORD '" + password + "'");
        ownerJdbc.execute("GRANT ALL ON TABLE cortex_release_marker TO cortex_runtime");

        JdbcTemplate runtimeJdbc = new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(),
                "cortex_runtime",
                password
        ));
        assertThat(runtimeJdbc.queryForObject(
                "SELECT current_user",
                String.class
        )).isEqualTo("cortex_runtime");
        int visibleRows = runtimeJdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_release_marker",
                Integer.class
        );
        assertThat(visibleRows).isPositive();
        assertThatThrownBy(() -> runtimeJdbc.update(
                "INSERT INTO cortex_release_marker (revision, marker) VALUES (?, ?)",
                "f".repeat(40),
                releaseMarker("f".repeat(40))
        )).isInstanceOf(RuntimeException.class);
        assertThat(runtimeJdbc.update(
                "UPDATE cortex_release_marker SET marker = marker WHERE revision = ?",
                FIRST_REVISION
        )).isZero();
        assertThat(runtimeJdbc.update(
                "DELETE FROM cortex_release_marker WHERE revision = ?",
                FIRST_REVISION
        )).isZero();
        assertThat(runtimeJdbc.queryForObject(
                "SELECT COUNT(*) FROM cortex_release_marker",
                Integer.class
        )).isEqualTo(visibleRows);
        assertThat(runtimeJdbc.queryForObject(
                "SELECT marker FROM cortex_release_marker WHERE revision = ?",
                String.class,
                FIRST_REVISION
        )).isEqualTo(FIRST_MARKER);
    }

    private void runMigration(
            PostgreSQLContainer<?> database,
            boolean writeEnabled,
            String revision,
            String marker
    ) {
        runMigration(
                database,
                database.getUsername(),
                database.getPassword(),
                writeEnabled,
                revision,
                marker
        );
    }

    private void runMigration(
            PostgreSQLContainer<?> database,
            String username,
            String password,
            boolean writeEnabled,
            String revision,
            String marker
    ) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--cortex.postgresql.release-marker.write-enabled=" + writeEnabled
        ));
        if (revision != null) {
            arguments.add("--CORTEX_RELEASE_REVISION=" + revision);
        }
        if (marker != null) {
            arguments.add("--CORTEX_RELEASE_MARKER=" + marker);
        }

        try (ConfigurableApplicationContext ignored =
                     new SpringApplicationBuilder(PostgresqlMigrationApplication.class)
                             .profiles("postgresql-migrate")
                             .properties(
                                     "spring.main.banner-mode=off",
                                     "spring.output.ansi.enabled=never",
                                     "logging.level.root=OFF"
                             )
                             .run(arguments.toArray(String[]::new))) {
            // Context close is the contract: the migration launcher is non-web.
        }
    }

    private JdbcTemplate jdbcTemplate(PostgreSQLContainer<?> database) {
        return new JdbcTemplate(new DriverManagerDataSource(
                database.getJdbcUrl(),
                database.getUsername(),
                database.getPassword()
        ));
    }

    private static String releaseMarker(String revision) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("cortex-release-v1:" + revision)
                            .getBytes(StandardCharsets.UTF_8)
            );
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
