package com.projeto.cortex.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresqlModeConfigurationGuardTest {

    @Test
    void executesBeforeDatabaseReadinessGuards() {
        PostgresqlModeConfigurationGuard guard = guard(
                "postgresql-migrate", "none", true, false, false
        );

        assertThat(BeanFactoryPostProcessor.class)
                .isAssignableFrom(PostgresqlModeConfigurationGuard.class);
        assertThat(guard.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void acceptsEachExactModeMatrix() {
        assertThatCode(() -> guard("postgresql-migrate", "none", true, false, false)
                .verifyConfiguration()).doesNotThrowAnyException();
        assertThatCode(() -> guard("postgresql-bootstrap", "none", false, true, false)
                .verifyConfiguration()).doesNotThrowAnyException();
        assertThatCode(() -> guard("postgresql-activation", "servlet", false, true, false)
                .verifyConfiguration()).doesNotThrowAnyException();
        assertThatCode(() -> guard("postgresql", "servlet", false, true, false)
                .verifyConfiguration()).doesNotThrowAnyException();
        assertThatCode(() -> guard("postgresql", "servlet", false, true, true)
                .verifyConfiguration()).doesNotThrowAnyException();
    }

    @Test
    void refusesMultiplePublicModes() {
        MockEnvironment environment = environment("none", false, true, false);
        environment.setActiveProfiles(
                "postgresql-common", "postgresql-bootstrap", "postgresql-migrate"
        );

        assertThatThrownBy(() -> new PostgresqlModeConfigurationGuard(environment)
                .verifyConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exatamente um modo PostgreSQL");
    }

    @Test
    void refusesPostgresqlCommonWithoutAPublicMode() {
        MockEnvironment environment = environment("none", false, false, false);
        environment.setActiveProfiles("postgresql-common");

        assertThatThrownBy(() -> new PostgresqlModeConfigurationGuard(environment)
                .verifyConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exatamente um modo PostgreSQL");
    }

    @Test
    void refusesAConfigurationThatDoesNotMatchItsMode() {
        assertThatThrownBy(() -> guard("postgresql-migrate", "servlet", true, false, false)
                .verifyConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgresql-migrate")
                .hasMessageContaining("web-application-type");
    }

    @Test
    void refusesMysqlOrAnotherPostgresqlDatabaseAsTheCanonicalDatasource() {
        MockEnvironment mysqlEnvironment = configuredEnvironment(
                "postgresql-bootstrap", "none", false, true, false
        );
        mysqlEnvironment.withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1/cortex_dev"
        );
        PostgresqlModeConfigurationGuard mysql = new PostgresqlModeConfigurationGuard(
                mysqlEnvironment
        );

        assertThatThrownBy(mysql::verifyConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("StaviasCortex")
                .hasMessageContaining("PostgreSQL");

        MockEnvironment otherDatabaseEnvironment = configuredEnvironment(
                "postgresql-bootstrap", "none", false, true, false
        );
        otherDatabaseEnvironment.withProperty(
                "spring.datasource.url",
                "jdbc:postgresql://127.0.0.1/other"
        );
        PostgresqlModeConfigurationGuard otherDatabase = new PostgresqlModeConfigurationGuard(
                otherDatabaseEnvironment
        );

        assertThatThrownBy(otherDatabase::verifyConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("StaviasCortex");
    }

    @Test
    void refusesMysqlDriverOrDefaultMigrationLocation() {
        MockEnvironment mysqlDriverEnvironment = configuredEnvironment(
                "postgresql-activation", "servlet", false, true, false
        );
        mysqlDriverEnvironment.withProperty(
                "spring.datasource.driver-class-name",
                "com.mysql.cj.jdbc.Driver"
        );
        PostgresqlModeConfigurationGuard mysqlDriver = new PostgresqlModeConfigurationGuard(
                mysqlDriverEnvironment
        );
        assertThatThrownBy(mysqlDriver::verifyConfiguration)
                .hasMessageContaining("spring.datasource.driver-class-name");

        MockEnvironment mysqlMigrationsEnvironment = configuredEnvironment(
                "postgresql-migrate", "none", true, false, false
        );
        mysqlMigrationsEnvironment.withProperty(
                "spring.flyway.locations",
                "classpath:db/migration"
        );
        PostgresqlModeConfigurationGuard mysqlMigrations =
                new PostgresqlModeConfigurationGuard(mysqlMigrationsEnvironment);
        assertThatThrownBy(mysqlMigrations::verifyConfiguration)
                .hasMessageContaining("spring.flyway.locations");
    }

    @Test
    void refusesUnsafeFlywayMigrationOverrides() {
        MockEnvironment baselineEnvironment = configuredEnvironment(
                "postgresql-migrate", "none", true, false, false
        );
        baselineEnvironment.withProperty("spring.flyway.baseline-on-migrate", "true");
        PostgresqlModeConfigurationGuard baseline = new PostgresqlModeConfigurationGuard(
                baselineEnvironment
        );
        assertThatThrownBy(baseline::verifyConfiguration)
                .hasMessageContaining("spring.flyway.baseline-on-migrate");

        MockEnvironment cleanEnvironment = configuredEnvironment(
                "postgresql-migrate", "none", true, false, false
        );
        cleanEnvironment.withProperty("spring.flyway.clean-disabled", "false");
        PostgresqlModeConfigurationGuard clean = new PostgresqlModeConfigurationGuard(
                cleanEnvironment
        );
        assertThatThrownBy(clean::verifyConfiguration)
                .hasMessageContaining("spring.flyway.clean-disabled");
    }

    @Test
    void refusesAnyCurrentSchemaVersionOtherThanV59() {
        MockEnvironment environment = configuredEnvironment(
                "postgresql-activation", "servlet", false, true, false
        );
        environment.withProperty("cortex.postgresql.required-schema-version", "47");

        assertThatThrownBy(() -> new PostgresqlModeConfigurationGuard(environment)
                .verifyConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cortex.postgresql.required-schema-version")
                .hasMessageContaining("59");
    }

    private PostgresqlModeConfigurationGuard guard(
            String publicProfile,
            String webApplicationType,
            boolean flyway,
            boolean schemaReadiness,
            boolean runtimeReady
    ) {
        return new PostgresqlModeConfigurationGuard(configuredEnvironment(
                publicProfile,
                webApplicationType, flyway, schemaReadiness, runtimeReady
        ));
    }

    private MockEnvironment configuredEnvironment(
            String publicProfile,
            String webApplicationType,
            boolean flyway,
            boolean schemaReadiness,
            boolean runtimeReady
    ) {
        MockEnvironment environment = environment(
                webApplicationType, flyway, schemaReadiness, runtimeReady
        );
        environment.setActiveProfiles("postgresql-common", publicProfile);
        return environment;
    }

    private MockEnvironment environment(
            String webApplicationType,
            boolean flyway,
            boolean schemaReadiness,
            boolean runtimeReady
    ) {
        return new MockEnvironment()
                .withProperty("spring.main.web-application-type", webApplicationType)
                .withProperty("spring.flyway.enabled", Boolean.toString(flyway))
                .withProperty(
                        "spring.datasource.url",
                        "jdbc:postgresql://127.0.0.1:5432/StaviasCortex"
                )
                .withProperty("spring.datasource.driver-class-name", "org.postgresql.Driver")
                .withProperty(
                        "spring.flyway.locations",
                        "classpath:db/migration-postgresql"
                )
                .withProperty("spring.flyway.baseline-on-migrate", "false")
                .withProperty("spring.flyway.clean-disabled", "true")
                .withProperty("cortex.postgresql.required-schema-version", "59")
                .withProperty(
                        "cortex.postgresql.schema-readiness.enabled",
                        Boolean.toString(schemaReadiness)
                )
                .withProperty(
                        "cortex.postgresql.runtime-ready",
                        Boolean.toString(runtimeReady)
                );
    }
}
