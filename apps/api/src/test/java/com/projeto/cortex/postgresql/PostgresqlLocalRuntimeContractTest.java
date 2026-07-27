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
                "only after V61 and a real ALFA bootstrap",
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
    void localApiLauncherEnforcesNormalRuntimeWithoutOtp() throws Exception {
        String runApi = read("scripts/dev/run-api.sh");

        assertNormalRuntimeLauncher(runApi);
        assertThat(runApi).contains(
                "cortex_require_postgres_url",
                "cortex_prepare_postgres_password",
                "cortex_preflight_operational_memory_cursor_hmac",
                "CORTEX_POSTGRES_RUNTIME_READY",
                "local,postgresql",
                "CORTEX_AUTH_DEV_ADMIN_ENABLED=\"false\"",
                "CORTEX_IMPORT_ENABLED=\"false\""
        );
    }

    @Test
    void localComposeLauncherUsesSelectedPortsWithoutOtp() throws Exception {
        String runCompose = read("scripts/dev/run-compose.sh");

        assertNormalRuntimeLauncher(runCompose);
        assertThat(runCompose).contains(
                "CORTEX_WEB_PORT=\"${CORTEX_WEB_PORT:-5173}\"",
                "CORTEX_API_PORT=\"${CORTEX_API_PORT:-8081}\"",
                "http://localhost:${CORTEX_WEB_PORT}",
                "http://127.0.0.1:${CORTEX_API_PORT}/api/health",
                "http://127.0.0.1:${CORTEX_API_PORT}/api/readiness"
        );
    }

    @Test
    void localDockerApiLauncherUsesSelectedPortsWithoutOtp() throws Exception {
        String runDocker = read("scripts/dev/run-api-docker.sh");

        assertNormalRuntimeLauncher(runDocker);
        assertThat(runDocker).contains(
                "CORTEX_WEB_PORT=\"${CORTEX_WEB_PORT:-5173}\"",
                "CORTEX_API_PORT=\"${CORTEX_API_PORT:-8081}\"",
                "-p \"127.0.0.1:${CORTEX_API_PORT}:8080\"",
                "-e CORTEX_WEB_PORT=\"$CORTEX_WEB_PORT\""
        );
    }

    @Test
    void productionRevenueRuntimeMountsNeitherActivationOtpNorLegacySmtp()
            throws Exception {
        String productionCompose = read("compose.production.example.yml");

        assertThat(productionCompose).contains(
                "SPRING_PROFILES_ACTIVE: production,postgresql",
                "CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE: /run/secrets/cortex_cpf_hmac"
        ).doesNotContain(
                "CORTEX_AUTH_OTP_HMAC_KEY_FILE",
                "cortex_otp_hmac",
                "CORTEX_EMAIL_PROVIDER",
                "CORTEX_EMAIL_SENDER_PROFILE_KEY",
                "CORTEX_SMTP_",
                "cortex_smtp_password",
                "CORTEX_FINANCE_EMAIL_"
        );
    }

    @Test
    void explicitActivationRetainsOtpSecretRequirement() throws Exception {
        String activation = read("scripts/dev/start-postgres-activation.sh");

        assertThat(activation).contains(
                "postgresql-activation",
                "cortex_require_secret_file CORTEX_AUTH_OTP_HMAC_KEY_FILE"
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
                "CORTEX_WEB_PORT=5173",
                "CORTEX_API_PORT=8081",
                "VITE_CORTEX_AUTH_MODE=postgresql",
                "CORTEX_IMPORT_ENABLED=false",
                "CORTEX_AUTH_DEV_ADMIN_ENABLED=false",
                "CORTEX_SYNC_ENABLED=true"
        ).doesNotContain(
                "CORTEX_AUTH_OTP_",
                "CORTEX_DB_URL=",
                "CORTEX_DB_PASSWORD=",
                "CORTEX_MYSQL_ROOT_PASSWORD=",
                "jdbc:mysql",
                "CORTEX_CORS_ALLOWED_ORIGINS=http://localhost:5173",
                "CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS=http://localhost:5173",
                "CORTEX_EMAIL_PROVIDER=",
                "CORTEX_EMAIL_SENDER_PROFILE_KEY=",
                "CORTEX_SMTP_",
                "CORTEX_FINANCE_EMAIL_"
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

    private static void assertNormalRuntimeLauncher(String launcher) {
        assertThat(launcher).contains(
                "CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE",
                "CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE",
                "CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE"
        ).doesNotContain(
                "CORTEX_AUTH_OTP_HMAC_KEY_FILE",
                "cortex_otp_hmac",
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
}
