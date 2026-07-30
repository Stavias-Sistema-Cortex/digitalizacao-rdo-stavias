package com.projeto.cortex.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Validates the public PostgreSQL launch mode before any database preflight. */
@Component
@Profile("postgresql-common")
public final class PostgresqlModeConfigurationGuard
        implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final List<String> PUBLIC_MODES = List.of(
            "postgresql",
            "postgresql-migrate",
            "postgresql-bootstrap",
            "postgresql-activation"
    );

    private Environment environment;

    public PostgresqlModeConfigurationGuard() {
    }

    PostgresqlModeConfigurationGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        verifyConfiguration();
    }

    void verifyConfiguration() {
        if (environment == null) {
            throw new IllegalStateException(
                    "Ambiente indisponível para validar o modo PostgreSQL."
            );
        }

        List<String> activePublicModes = Arrays.stream(environment.getActiveProfiles())
                .filter(PUBLIC_MODES::contains)
                .toList();
        if (activePublicModes.size() != 1) {
            throw new IllegalStateException(
                    "Córtex exige exatamente um modo PostgreSQL público ativo; encontrados: "
                            + activePublicModes
            );
        }

        String mode = activePublicModes.getFirst();
        ModeMatrix expected = ModeMatrix.forMode(mode);
        require(
                mode,
                "cortex.postgresql.required-schema-version",
                PostgresqlSchemaVersion.REQUIRED
        );
        requireCanonicalPostgresqlDatasource(mode);
        require(mode, "spring.datasource.driver-class-name", "org.postgresql.Driver");
        requireExact(mode, "spring.datasource.hikari.schema", "public");
        requireExact(
                mode,
                PostgresqlJdbcTlsPolicy.HIKARI_SSL_FACTORY_PROPERTY,
                PostgresqlJdbcTlsPolicy.JAVA_SSL_FACTORY
        );
        requireEffectiveJavaTrustStore(mode);
        require(
                mode,
                "spring.flyway.locations",
                "classpath:db/migration-postgresql"
        );
        require(mode, "spring.main.web-application-type", expected.webApplicationType());
        require(mode, "spring.flyway.enabled", expected.flywayEnabled());
        require(
                mode,
                "cortex.postgresql.schema-readiness.enabled",
                expected.schemaReadinessEnabled()
        );

        if (!"postgresql".equals(mode)) {
            require(mode, "cortex.postgresql.runtime-ready", false);
        }
        if ("postgresql-migrate".equals(mode)) {
            requireExact(mode, "spring.flyway.default-schema", "public");
            requireExact(mode, "spring.flyway.schemas", "public");
            require(mode, "spring.flyway.create-schemas", false);
            require(mode, "spring.flyway.baseline-on-migrate", false);
            require(mode, "spring.flyway.clean-disabled", true);
        }
    }

    private void requireCanonicalPostgresqlDatasource(String mode) {
        String url = environment.getProperty("spring.datasource.url");
        String urlWithoutQuery = url == null ? "" : url.split("\\?", 2)[0];
        if (!urlWithoutQuery.startsWith("jdbc:postgresql://")
                || !urlWithoutQuery.endsWith("/StaviasCortex")) {
            throw new IllegalStateException(
                    "Modo " + mode + " exige datasource PostgreSQL canônico StaviasCortex."
            );
        }
    }

    private void requireEffectiveJavaTrustStore(String mode) {
        String url = environment.getRequiredProperty("spring.datasource.url");
        if (!PostgresqlJdbcTlsPolicy.usesJavaTrustStore(url)) {
            throw new IllegalStateException(
                    "Modo " + mode + " exige sslfactory="
                            + PostgresqlJdbcTlsPolicy.JAVA_SSL_FACTORY
                            + " efetiva na URL PostgreSQL."
            );
        }
    }

    private void require(String mode, String property, Object expected) {
        String actual = environment.getProperty(property);
        if (actual == null || !expected.toString().equalsIgnoreCase(actual.trim())) {
            throw new IllegalStateException(
                    "Modo " + mode + " exige " + property + "=" + expected
                            + " (encontrado " + actual + ")."
            );
        }
    }

    private void requireExact(String mode, String property, String expected) {
        String actual = environment.getProperty(property);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Modo " + mode + " exige " + property + "=" + expected
                            + " (encontrado " + actual + ")."
            );
        }
    }

    private record ModeMatrix(
            String webApplicationType,
            boolean flywayEnabled,
            boolean schemaReadinessEnabled
    ) {
        private static ModeMatrix forMode(String mode) {
            return switch (mode) {
                case "postgresql-migrate" -> new ModeMatrix("none", true, false);
                case "postgresql-bootstrap" -> new ModeMatrix("none", false, true);
                case "postgresql-activation", "postgresql" ->
                        new ModeMatrix("servlet", false, true);
                default -> throw new IllegalStateException("Modo PostgreSQL desconhecido: " + mode);
            };
        }
    }
}
