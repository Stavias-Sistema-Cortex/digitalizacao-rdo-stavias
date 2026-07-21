package com.projeto.cortex.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresqlProvisionerContractTest {

    private static final Path PROVISIONER = Path.of("../../scripts/dev/init-postgres-cortex.sh");
    private static final Path COMMON_GUARDS = Path.of(
            "../../scripts/dev/postgres-cortex-common.sh"
    );
    private static final Path ENVIRONMENT_TEMPLATE = Path.of("../../.env.postgresql.example");

    @Test
    void provisionsTheExactDatabaseIdempotentlyWithoutPrintingSecrets() throws IOException {
        assertTrue(Files.isRegularFile(PROVISIONER),
                "the local PostgreSQL provisioner must exist");
        assertTrue(Files.isRegularFile(COMMON_GUARDS),
                "the PostgreSQL provisioner must use the shared secret-file guards");

        String script = Files.readString(PROVISIONER);
        String commonGuards = Files.readString(COMMON_GUARDS);

        assertTrue(script.contains("postgres-cortex-common.sh"));
        assertTrue(script.contains("cortex_require_postgres_url"));
        assertTrue(script.contains("cortex_prepare_postgres_password"));
        assertTrue(script.contains("DB_NAME=\"$(cortex_postgres_database_name)\""));
        assertTrue(commonGuards.contains("StaviasCortex"));
        assertTrue(commonGuards.contains("CORTEX_POSTGRES_PASSWORD_FILE"));
        assertTrue(script.contains("SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}'"));
        assertTrue(script.contains("CREATE DATABASE \\\"${DB_NAME}\\\""),
                "the mixed-case database name must be quoted during creation");
        assertTrue(commonGuards.contains("SELECT current_database()"),
                "the provisioner must verify its final connection");
        assertFalse(script.contains("echo \"${PGPASSWORD}"),
                "the provisioner must never print a PostgreSQL password");
        assertFalse(script.contains("echo \"${CORTEX_POSTGRES_PASSWORD}"),
                "the provisioner must never print a configured password");
    }

    @Test
    void documentsOnlyNonSecretPostgresqlConfiguration() throws IOException {
        assertTrue(Files.isRegularFile(ENVIRONMENT_TEMPLATE),
                "an opt-in PostgreSQL environment template must exist");

        String template = Files.readString(ENVIRONMENT_TEMPLATE);

        assertTrue(template.contains("CORTEX_POSTGRES_USER=<POSTGRES_ROLE>"));
        assertTrue(template.contains("CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/StaviasCortex"));
        assertTrue(template.contains("CORTEX_POSTGRES_PASSWORD_FILE=<ABSOLUTE_PATH_TO_POSTGRES_PASSWORD_FILE>"));
        assertTrue(template.contains("CORTEX_ACADEMY_DB_URL=<ACADEMY_READ_ONLY_JDBC_URL>"));
        assertTrue(template.contains("CORTEX_ZELADORIA_DB_URL=<ZELADORIA_READ_ONLY_JDBC_URL>"));
        assertFalse(template.contains("joaolucas"),
                "the example must not embed a local operator identity");
        assertFalse(template.contains("CORTEX_POSTGRES_PASSWORD="),
                "the example must not contain a password placeholder that can be mistaken for a secret");
    }
}
