package com.projeto.cortex.auth.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresqlBootstrapProfileGuardTest {

    @Test
    void acceptsOnlyTheExplicitNonWebV44ReadinessBootstrapMode() {
        PostgresqlBootstrapProfileGuard guard = new PostgresqlBootstrapProfileGuard(
                configuredEnvironment()
        );

        assertThatCode(guard::verifyConfiguration).doesNotThrowAnyException();
        assertThat(guard.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
    }

    @Test
    void rejectsDisabledWebOrUnsafeBootstrapConfiguration() {
        MockEnvironment disabled = configuredEnvironment();
        disabled.setProperty("cortex.postgresql.bootstrap.enabled", "false");
        MockEnvironment web = configuredEnvironment();
        web.setProperty("spring.main.web-application-type", "servlet");
        MockEnvironment noV44Readiness = configuredEnvironment();
        noV44Readiness.setProperty("cortex.postgresql.schema-readiness.enabled", "false");

        assertThatThrownBy(() -> new PostgresqlBootstrapProfileGuard(disabled)
                .verifyConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configuração de bootstrap PostgreSQL inválida.");
        assertThatThrownBy(() -> new PostgresqlBootstrapProfileGuard(web)
                .verifyConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configuração de bootstrap PostgreSQL inválida.");
        assertThatThrownBy(() -> new PostgresqlBootstrapProfileGuard(noV44Readiness)
                .verifyConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configuração de bootstrap PostgreSQL inválida.");
    }

    private static MockEnvironment configuredEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("postgresql-common", "postgresql-bootstrap");
        environment.setProperty("spring.main.web-application-type", "none");
        environment.setProperty("cortex.postgresql.bootstrap.enabled", "true");
        environment.setProperty("cortex.postgresql.schema-readiness.enabled", "true");
        environment.setProperty("spring.flyway.enabled", "false");
        environment.setProperty("cortex.postgresql.runtime-ready", "false");
        return environment;
    }
}
