package com.projeto.cortex.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PostgresqlLocalRuntimeContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("../..");
    private static final String CANONICAL_DATABASE =
            "Sta" + "vias" + "Cortex";

    @Test
    void localComposeUsesOnlyTheCanonicalPostgresqlRuntime() throws Exception {
        String compose = read("compose.local.yml");

        assertThat(compose).contains(
                "SPRING_PROFILES_ACTIVE: local,postgresql",
                "SPRING_CONFIG_IMPORT: configtree:/run/secrets/",
                "CORTEX_POSTGRES_DOCKER_URL:",
                "CORTEX_POSTGRES_RUNTIME_READY:",
                "only after V60 and a real ALFA bootstrap",
                "target: CORTEX_POSTGRES_PASSWORD",
                "CORTEX_AUTH_DEV_ADMIN_ENABLED: \"false\"",
                "CORTEX_AUTH_PROVISIONING_ENABLED: \"false\"",
                "CORTEX_IMPORT_ENABLED: \"false\"",
                "CORTEX_SYNC_ENABLED: \"true\"",
                "VITE_CORTEX_AUTH_MODE: postgresql",
                "127.0.0.1:${CORTEX_API_PORT:-8081}:8080",
                "127.0.0.1:${CORTEX_WEB_PORT:-5173}:8080"
        ).doesNotContain(
                "cortex-mysql",
                "jdbc:mysql",
                "cortex_dev",
                "CORTEX_DB_",
                "CORTEX_MYSQL_ROOT_PASSWORD",
                "VITE_CORTEX_AUTH_MODE: legacy",
                "only after V59 and a real ALFA bootstrap",
                "\n      CORTEX_POSTGRES_PASSWORD:"
        );
    }

    @Test
    void localLaunchersEnforceV60RealAlfaAndSecretFiles() throws Exception {
        String runApi = read("scripts/dev/run-api.sh");
        String runCompose = read("scripts/dev/run-compose.sh");
        String runDocker = read("scripts/dev/run-api-docker.sh");
        String launchers = runApi + runCompose + runDocker;

        assertThat(launchers).contains(
                "cortex_require_postgres_url",
                "CORTEX_POSTGRES_RUNTIME_READY",
                "local,postgresql",
                "CORTEX_POSTGRES_PASSWORD_FILE",
                "CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE",
                "CORTEX_AUTH_OTP_HMAC_KEY_FILE",
                "CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE",
                "CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE",
                "CORTEX_AUTH_DEV_ADMIN_ENABLED=\"false\"",
                "CORTEX_IMPORT_ENABLED=\"false\""
        ).doesNotContain(
                "CORTEX_DB_PASSWORD",
                "CORTEX_DB_URL",
                "CORTEX_MYSQL_ROOT_PASSWORD",
                "jdbc:mysql",
                "cortex_dev",
                "CORTEX_AUTH_DEV_ADMIN_ENABLED=\"true\"",
                "CORTEX_IMPORT_ENABLED=\"true\"",
                "bootstrap-postgres-alfa.sh"
        );
    }

    @Test
    void environmentAndWebDefaultsSelectPostgresqlExplicitly() throws Exception {
        String rootEnvironment = read(".env.example");
        String webEnvironment = read("apps/web/.env.example");

        assertThat(rootEnvironment).contains(
                "CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/"
                        + CANONICAL_DATABASE,
                "CORTEX_POSTGRES_DOCKER_URL=jdbc:postgresql://"
                        + "host.docker.internal:5432/" + CANONICAL_DATABASE,
                "CORTEX_POSTGRES_PASSWORD_FILE=",
                "SPRING_PROFILES_ACTIVE=local,postgresql",
                "CORTEX_POSTGRES_RUNTIME_READY=false",
                "VITE_CORTEX_AUTH_MODE=postgresql",
                "CORTEX_IMPORT_ENABLED=false",
                "CORTEX_AUTH_DEV_ADMIN_ENABLED=false",
                "CORTEX_SYNC_ENABLED=true"
        ).doesNotContain(
                "CORTEX_DB_URL=",
                "CORTEX_DB_PASSWORD=",
                "CORTEX_MYSQL_ROOT_PASSWORD=",
                "jdbc:mysql"
        );
        assertThat(webEnvironment)
                .contains("VITE_CORTEX_AUTH_MODE=postgresql")
                .doesNotContain("VITE_CORTEX_AUTH_MODE=legacy");
    }

    @Test
    void localProfileDoesNotSynthesizeASecondOtpSecret() throws Exception {
        String application = read("apps/api/src/main/resources/application.yml");
        String localProfile = read("apps/api/src/main/resources/application-local.yml");

        assertThat(application).contains(
                "hmac-key-file: ${CORTEX_AUTH_OTP_HMAC_KEY_FILE:}",
                "hmac-key-inline: ${CORTEX_AUTH_OTP_HMAC_KEY:}"
        );
        assertThat(localProfile).doesNotContain(
                "hmac-key-inline",
                "random.uuid"
        );
    }

    @Test
    void developmentRunbookIsRevenueOnlyAndForbidsSyntheticReadiness()
            throws Exception {
        String runbook = read("docs/dev-runbook.md");

        assertThat(runbook).contains(
                CANONICAL_DATABASE,
                "Rastreio de receita",
                "Serviços e preços versionados",
                "PDOR de receita",
                "Não crie ALFA",
                "CORTEX_POSTGRES_RUNTIME_READY=true"
        ).doesNotContain(
                "MySQL: `127.0.0.1:3307`",
                "smoke-sta" + "via-sync.sh",
                "seed de custos",
                "seed de margens"
        );
    }

    private static String read(String relativePath) throws Exception {
        return Files.readString(REPOSITORY_ROOT.resolve(relativePath));
    }
}
