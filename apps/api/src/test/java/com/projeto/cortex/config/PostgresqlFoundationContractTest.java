package com.projeto.cortex.config;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresqlFoundationContractTest {

    private static final Pattern REQUIRED_SCHEMA_VERSION =
            Pattern.compile("required-schema-version:\\s*([0-9._]+)");
    private static final Pattern MIGRATION_FILE_VERSION =
            Pattern.compile("^V([0-9_]+)__.+\\.sql$");
    private static final Path POM = Path.of("pom.xml");
    private static final Path POSTGRESQL_PROFILE = Path.of("src/main/resources/application-postgresql.yml");
    private static final Path POSTGRESQL_COMMON_PROFILE = Path.of(
            "src/main/resources/application-postgresql-common.yml"
    );
    private static final Path POSTGRESQL_MIGRATIONS = Path.of("src/main/resources/db/migration-postgresql");

    @Test
    void keepsOnlyTheCanonicalPostgresqlRuntimeDependencies() throws IOException {
        String pom = Files.readString(POM);

        assertTrue(pom.contains("<artifactId>postgresql</artifactId>"),
                "PostgreSQL JDBC must be available for the opt-in profile");
        assertTrue(pom.contains("<artifactId>flyway-database-postgresql</artifactId>"),
                "Flyway PostgreSQL support must be available for the opt-in profile");
        assertTrue(pom.contains("<artifactId>mysql-connector-j</artifactId>"),
                "the separately configured, read-only Academy source needs its JDBC driver");
        assertFalse(pom.contains("<artifactId>flyway-mysql</artifactId>"),
                "the canonical runtime must not load retired MySQL migrations");
    }

    @Test
    void isolatesThePostgresqlProfileAndItsFlywayLocation() throws IOException {
        assertTrue(Files.isRegularFile(POSTGRESQL_PROFILE),
                "the opt-in PostgreSQL profile must exist");
        assertTrue(Files.isRegularFile(POSTGRESQL_COMMON_PROFILE),
                "the shared PostgreSQL datasource profile must exist");
        assertTrue(Files.isDirectory(POSTGRESQL_MIGRATIONS),
                "PostgreSQL migrations must use their own directory");

        String profile = Files.readString(POSTGRESQL_PROFILE);
        String commonProfile = Files.readString(POSTGRESQL_COMMON_PROFILE);

        assertTrue(profile.contains("on-profile: postgresql"));
        assertTrue(commonProfile.contains("on-profile: postgresql-common"));
        assertTrue(commonProfile.contains("${CORTEX_POSTGRES_URL}"));
        assertFalse(commonProfile.contains("${CORTEX_POSTGRES_URL:"),
                "the canonical PostgreSQL URL must be supplied explicitly");
        assertTrue(commonProfile.contains("${CORTEX_POSTGRES_USER:joaolucas}"));
        assertTrue(commonProfile.contains("${CORTEX_POSTGRES_PASSWORD:}"));
        assertFalse(commonProfile.contains("CORTEX_DB_URL"),
                "the PostgreSQL datasource must not reuse the MySQL connection URL variable");
        assertTrue(commonProfile.contains("classpath:db/migration-postgresql"));
        assertTrue(profile.contains("enabled: false"),
                "normal PostgreSQL runtime must never execute migrations");
        assertTrue(commonProfile.contains("required-schema-version: 63"));
        assertFalse(commonProfile.contains("classpath:db/migration\n"),
                "the PostgreSQL profile must not run the MySQL migration directory");
        assertFalse((profile + commonProfile).toLowerCase().contains("supabase"),
                "the PostgreSQL foundation must not introduce Supabase");
    }

    @Test
    void requiredSchemaVersionTracksTheLatestPostgresqlMigration()
            throws IOException {
        Matcher configured = REQUIRED_SCHEMA_VERSION.matcher(
                Files.readString(POSTGRESQL_COMMON_PROFILE)
        );
        assertTrue(
                configured.find(),
                "the PostgreSQL profile must declare its required schema version"
        );

        MigrationVersion latest;
        try (var files = Files.list(POSTGRESQL_MIGRATIONS)) {
            latest = files
                    .map(path -> path.getFileName().toString())
                    .map(MIGRATION_FILE_VERSION::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> matcher.group(1).replace('_', '.'))
                    .map(MigrationVersion::fromVersion)
                    .max(MigrationVersion::compareTo)
                    .orElseThrow();
        }

        MigrationVersion required = MigrationVersion.fromVersion(
                configured.group(1).replace('_', '.')
        );
        assertTrue(
                required.equals(latest),
                "required-schema-version must match the latest PostgreSQL migration"
        );
        assertTrue(
                PostgresqlSchemaVersion.REQUIRED.equals(configured.group(1)),
                "Java readiness and the profile must use the same schema version"
        );
    }
}
