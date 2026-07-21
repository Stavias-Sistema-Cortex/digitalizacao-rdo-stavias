package com.projeto.cortex.postgresql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlCleanStartOperatorContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("../..");
    private static final Path DOCUMENTATION = REPOSITORY_ROOT.resolve(
            "docs/operations/cortex-postgresql-clean-start.md"
    );
    private static final Path ENVIRONMENT_TEMPLATE = REPOSITORY_ROOT.resolve(
            ".env.postgresql.example"
    );
    private static final Path COMMON_GUARDS = script("postgres-cortex-common.sh");
    private static final Path INITIALIZATION = script("init-postgres-cortex.sh");
    private static final Path MIGRATION = script("migrate-postgres-cortex.sh");
    private static final Path BOOTSTRAP = script("bootstrap-postgres-alfa.sh");
    private static final Path ACTIVATION = script("start-postgres-activation.sh");
    private static final Path RUNTIME_RELEASE_CHECK = script(
            "check-postgres-runtime-release.sh"
    );
    private static final Path REHEARSAL = script(
            "verify-postgres-cortex-clean-start.sh"
    );
    private static final Pattern INLINE_CPF = Pattern.compile(
            "(?<!\\d)\\d{3}[.\\s]?\\d{3}[.\\s]?\\d{3}-?\\d{2}(?!\\d)"
    );
    private static final Pattern RAW_SOURCE_PASSWORD = Pattern.compile(
            "(?mi)^\\s*CORTEX_(?:ACADEMY|ZELADORIA)_DB_PASSWORD\\s*="
                    + "\\s*(?!<|\\$\\{|$).+$"
    );
    private static final Pattern JDBC_CREDENTIALS = Pattern.compile(
            "(?i)jdbc:(?:mysql|postgresql)://[^\\s/@:]+:[^\\s/@]+@"
    );

    @Test
    void operatorTransitionsUseOnlyTheirDedicatedPublicProfileAndMinimalLauncher()
            throws IOException {
        String initialization = contentOf(INITIALIZATION);
        assertThat(Files.isExecutable(INITIALIZATION)).isTrue();
        assertThat(initialization).contains(
                "cortex_require_postgres_url",
                "cortex_prepare_postgres_password",
                "cortex_verify_target_database",
                "CREATE DATABASE"
        );
        assertScriptLaunches(
                MIGRATION,
                "postgresql-migrate",
                "com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication"
        );
        assertScriptLaunches(
                BOOTSTRAP,
                "postgresql-bootstrap",
                "com.projeto.cortex.postgresql.bootstrap.PostgresqlBootstrapApplication"
        );
        assertScriptLaunches(
                ACTIVATION,
                "postgresql-activation",
                "com.projeto.cortex.postgresql.activation.PostgresqlActivationApplication"
        );
        assertThat(contentOf(BOOTSTRAP)).contains(
                "export CORTEX_POSTGRES_BOOTSTRAP_ENABLED=true"
        ).doesNotContain("-Dcortex.postgresql.bootstrap.enabled");

        String releaseCheck = contentOf(RUNTIME_RELEASE_CHECK);
        assertThat(releaseCheck).contains(
                "postgresql",
                "CORTEX_POSTGRES_RUNTIME_READY=true"
        );
        assertThat(releaseCheck).doesNotContain(
                "spring-boot:run",
                "CortexApplication"
        );

        String rehearsal = contentOf(REHEARSAL);
        assertThat(rehearsal).contains("-Ppostgresql-it", "verify");
        assertThat(rehearsal).doesNotContain(
                "migrate-postgres-cortex.sh",
                "bootstrap-postgres-alfa.sh"
        );
    }

    @Test
    void operatorSurfaceNeverContainsLegacyOrSecretBearingCommands()
            throws IOException {
        String operatorSurface = allOperatorContent();
        String commonGuards = contentOf(COMMON_GUARDS);

        assertThat(operatorSurface).doesNotContain(
                "run-api.sh",
                "import-all.sh",
                "import-assets.sh",
                "import-colaboradores.sh",
                "import-obras.sh",
                "import-programacoes.sh",
                "sync-runs.sh",
                "CORTEX_DB_URL",
                "set -x",
                "flyway.clean",
                "baselineOnMigrate=true"
        );
        assertThat(operatorSurface).doesNotContainIgnoringCase("supabase");
        assertThat(INLINE_CPF.matcher(operatorSurface).find())
                .as("operator instructions must name the owner-only CPF file, never a CPF value")
                .isFalse();
        assertThat(RAW_SOURCE_PASSWORD.matcher(operatorSurface).find())
                .as("Academy and Zeladoria credentials must remain external secret values")
                .isFalse();
        assertThat(JDBC_CREDENTIALS.matcher(operatorSurface).find())
                .as("operator artifacts must not embed JDBC user/password credentials")
                .isFalse();
        assertThat(commonGuards).contains(
                "if [[ -z \"${CORTEX_POSTGRES_PASSWORD_FILE:-}\" ]]",
                "CORTEX_POSTGRES_PASSWORD must be supplied via CORTEX_POSTGRES_PASSWORD_FILE"
        );
    }

    @Test
    void documentationStatesTheCanonicalDataBoundariesAndFalseRuntimeGate()
            throws IOException {
        String documentation = contentOf(DOCUMENTATION);

        assertThat(documentation).containsIgnoringCase("MySQL");
        assertThat(documentation).containsIgnoringCase("source-only");
        assertThat(documentation).contains("Academy", "Zeladoria", "PostgreSQL");
        assertThat(documentation).containsIgnoringCase("operacionais do Córtex");
        assertThat(documentation).containsIgnoringCase("object storage");
        assertThat(documentation).containsIgnoringCase("storage_key");
        assertThat(documentation).contains(
                "CORTEX_POSTGRES_RUNTIME_READY=false",
                "falha fechada"
        );
    }

    private void assertScriptLaunches(
            Path script,
            String publicProfile,
            String minimalLauncher
    ) throws IOException {
        assertThat(Files.isRegularFile(script)).as("operator script %s", script).isTrue();
        assertThat(Files.isExecutable(script)).as("operator script %s", script).isTrue();

        String content = Files.readString(script);
        assertThat(content).contains(publicProfile, minimalLauncher);
        assertThat(content).doesNotContain(
                "CortexApplication",
                "run-api.sh",
                "CORTEX_DB_URL"
        );
    }

    private String allOperatorContent() throws IOException {
        StringBuilder content = new StringBuilder();
        for (Path artifact : List.of(
                MIGRATION,
                BOOTSTRAP,
                ACTIVATION,
                RUNTIME_RELEASE_CHECK,
                REHEARSAL,
                COMMON_GUARDS,
                INITIALIZATION,
                DOCUMENTATION,
                ENVIRONMENT_TEMPLATE
        )) {
            content.append(contentOf(artifact)).append('\n');
        }
        return content.toString();
    }

    private String contentOf(Path path) throws IOException {
        assertThat(Files.isRegularFile(path)).as("operator artifact %s", path).isTrue();
        return Files.readString(path);
    }

    private static Path script(String filename) {
        return REPOSITORY_ROOT.resolve("scripts/dev").resolve(filename);
    }
}
