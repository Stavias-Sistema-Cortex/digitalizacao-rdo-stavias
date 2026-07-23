package com.projeto.cortex.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresqlFoundationContractTest {

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
        assertFalse(pom.contains("<artifactId>mysql-connector-j</artifactId>"),
                "the canonical runtime must not depend on the retired MySQL driver");
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
        assertTrue(commonProfile.contains("required-schema-version: 54"));
        assertFalse(commonProfile.contains("classpath:db/migration\n"),
                "the PostgreSQL profile must not run the MySQL migration directory");
        assertFalse((profile + commonProfile).toLowerCase().contains("supabase"),
                "the PostgreSQL foundation must not introduce Supabase");
    }
}
