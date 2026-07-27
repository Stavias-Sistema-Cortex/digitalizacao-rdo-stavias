package com.projeto.cortex.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Static release contract for the production authentication boundary. */
class ProductionSecurityContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("../..");

    @Test
    void productionExampleUsesCanonicalPostgresqlSecretsAndAnExplicitTrustedProxyNetwork()
            throws Exception {
        String compose = Files.readString(
                REPOSITORY_ROOT.resolve("compose.production.example.yml")
        );

        assertThat(compose).contains(
                "SPRING_PROFILES_ACTIVE: production,postgresql",
                "SPRING_CONFIG_IMPORT: configtree:/run/secrets/",
                "CORTEX_POSTGRES_URL:",
                "target: CORTEX_POSTGRES_PASSWORD",
                "CORTEX_POSTGRES_PASSWORD_FILE:",
                "CORTEX_AUTH_TRUSTED_PROXY_CIDRS: 172.30.0.0/24",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID:",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE: /run/secrets/cortex_memory_cursor_hmac",
                "- cortex_memory_cursor_hmac",
                "cortex_memory_cursor_hmac:",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE:",
                "CORTEX_ACADEMY_DB_URL:",
                "CORTEX_ACADEMY_DB_USER:",
                "target: CORTEX_ACADEMY_DB_PASSWORD",
                "CORTEX_ACADEMY_DB_PASSWORD_FILE:",
                "CORTEX_ZELADORIA_DB_URL:",
                "CORTEX_ZELADORIA_DB_USER:",
                "target: CORTEX_ZELADORIA_DB_PASSWORD",
                "CORTEX_ZELADORIA_DB_PASSWORD_FILE:",
                "${CORTEX_WEB_BIND_ADDRESS:-127.0.0.1}:${CORTEX_WEB_PORT:-8080}:8080",
                "VITE_CORTEX_AUTH_MODE: postgresql",
                "subnet: 172.30.0.0/24"
        ).doesNotContain(
                "CORTEX_DB_URL:",
                "Set the production MySQL",
                "\n      CORTEX_POSTGRES_PASSWORD:",
                "\n      CORTEX_ACADEMY_DB_PASSWORD:",
                "\n      CORTEX_ZELADORIA_DB_PASSWORD:",
                "CORTEX_POSTGRES_PASSWORD_SECRET_FILE",
                "CORTEX_MEMORY_CURSOR_HMAC_SECRET_FILE",
                "\n      AWS_SECRET_ACCESS_KEY:"
        );
    }

    @Test
    void documentedProductionEnvironmentMatchesTheComposeSecretContract() throws Exception {
        String environment = Files.readString(
                REPOSITORY_ROOT.resolve(".env.postgresql.example")
        );

        assertThat(environment).contains(
                "CORTEX_POSTGRES_PASSWORD_FILE=",
                "CORTEX_PUBLIC_ORIGIN=",
                "CORTEX_WEB_BIND_ADDRESS=127.0.0.1",
                "CORTEX_AUTH_WEBAUTHN_RP_ID=",
                "CORTEX_AUTH_OFFLINE_GRANT_KEY_ID=",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID=",
                "VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=",
                "CORTEX_ACADEMY_DB_URL=",
                "CORTEX_ACADEMY_DB_USER=",
                "CORTEX_ACADEMY_DB_PASSWORD_FILE=",
                "CORTEX_ZELADORIA_DB_URL=",
                "CORTEX_ZELADORIA_DB_USER=",
                "CORTEX_ZELADORIA_DB_PASSWORD_FILE="
        ).doesNotContain(
                "CORTEX_DB_PASSWORD=",
                "CORTEX_POSTGRES_PASSWORD_SECRET_FILE="
        );
    }

    @Test
    void webImageRequiresExplicitAuthenticationAndOfflineSigningConfiguration()
            throws Exception {
        String dockerfile = Files.readString(
                REPOSITORY_ROOT.resolve("apps/web/Dockerfile")
        );
        String validator = Files.readString(
                REPOSITORY_ROOT.resolve(
                        "apps/web/validate-docker-build-args.sh"
                )
        );

        assertThat(dockerfile).contains(
                "ARG VITE_CORTEX_AUTH_MODE\n",
                "ARG VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256\n",
                "RUN sh ./validate-docker-build-args.sh"
        ).doesNotContain("ARG VITE_CORTEX_AUTH_MODE=legacy");
        assertThat(validator).contains(
                "case \"${VITE_CORTEX_AUTH_MODE:-}\" in",
                "legacy|postgresql)",
                "fingerprint=\"${VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256:-}\"",
                "[ \"${#fingerprint}\" -ne 43 ]",
                "*[!A-Za-z0-9_-]*)"
        );
    }
}
