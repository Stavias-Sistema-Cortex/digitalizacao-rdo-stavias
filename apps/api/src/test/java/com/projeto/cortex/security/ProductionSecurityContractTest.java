package com.projeto.cortex.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Static release contract for the production authentication boundary. */
class ProductionSecurityContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("../..");

    @Test
    void productionExampleUsesPostgresqlOtpAndAnExplicitTrustedProxyNetwork()
            throws Exception {
        String compose = Files.readString(
                REPOSITORY_ROOT.resolve("compose.production.example.yml")
        );

        assertThat(compose).contains(
                "SPRING_PROFILES_ACTIVE: production,postgresql",
                "CORTEX_POSTGRES_URL:",
                "CORTEX_AUTH_TRUSTED_PROXY_CIDRS: 172.30.0.0/24",
                "VITE_CORTEX_AUTH_MODE: postgresql",
                "subnet: 172.30.0.0/24"
        ).doesNotContain(
                "CORTEX_DB_URL:",
                "Set the production MySQL"
        );
    }

    @Test
    void webImageRequiresAnExplicitAuthenticationMode() throws Exception {
        String dockerfile = Files.readString(
                REPOSITORY_ROOT.resolve("apps/web/Dockerfile")
        );

        assertThat(dockerfile).contains(
                "ARG VITE_CORTEX_AUTH_MODE\n",
                "test \"${VITE_CORTEX_AUTH_MODE}\" = \"legacy\"",
                "test \"${VITE_CORTEX_AUTH_MODE}\" = \"postgresql\""
        ).doesNotContain("ARG VITE_CORTEX_AUTH_MODE=legacy");
    }
}
