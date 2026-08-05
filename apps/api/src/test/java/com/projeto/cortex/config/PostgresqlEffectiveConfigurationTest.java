package com.projeto.cortex.config;

import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresqlEffectiveConfigurationTest {

    @Test
    void resolvesMigrationModeWithoutEitherReadinessGuard() {
        withProfile("postgresql-migrate", context -> assertResolved(
                context, "none", true, false, false
        ));
    }

    @Test
    void resolvesMigrationReleaseMarkerWriterFromItsDedicatedEnvironmentProperty() {
        contextRunner("postgresql-migrate").run(context -> assertThat(
                context.getEnvironment().getProperty(
                        "cortex.postgresql.release-marker.write-enabled",
                        Boolean.class
                )
        ).isFalse());

        contextRunner("postgresql-migrate")
                .withPropertyValues(
                        "CORTEX_POSTGRES_RELEASE_MARKER_WRITE_ENABLED=true"
                )
                .run(context -> assertThat(
                        context.getEnvironment().getProperty(
                                "cortex.postgresql.release-marker.write-enabled",
                                Boolean.class
                        )
                ).isTrue());
    }

    @Test
    void resolvesBootstrapModeWithSchemaReadinessOnly() {
        withProfile("postgresql-bootstrap", context -> assertResolved(
                context, "none", false, true, false
        ));
    }

    @Test
    void resolvesActivationModeWithSchemaReadinessOnly() {
        withProfile("postgresql-activation", context -> assertResolved(
                context, "servlet", false, true, false
        ));
    }

    @Test
    void resolvesNormalRuntimeWithSchemaReadinessAndFalseOwnerGateByDefault() {
        withProfile("postgresql", context -> assertResolved(
                context, "servlet", false, true, false
        ));
    }

    @Test
    void resolvesNormalRuntimeOwnerGateFromTheDedicatedEnvironmentProperty() {
        contextRunner("postgresql")
                .withPropertyValues("CORTEX_POSTGRES_RUNTIME_READY=true")
                .run(context -> assertResolved(
                        context, "servlet", false, true, true
                ));
    }

    @Test
    void resolvesReleaseMarkerRequirementFromItsDedicatedEnvironmentProperty() {
        contextRunner("postgresql").run(context -> assertThat(
                context.getEnvironment().getProperty(
                        "cortex.postgresql.release-marker.required",
                        Boolean.class
                )
        ).isFalse());

        contextRunner("postgresql")
                .withPropertyValues(
                        "CORTEX_POSTGRES_RELEASE_MARKER_REQUIRED=true"
                )
                .run(context -> assertThat(
                        context.getEnvironment().getProperty(
                                "cortex.postgresql.release-marker.required",
                                Boolean.class
                        )
                ).isTrue());
    }

    @Test
    void resolvesAcademyReadinessFreshnessDefaultAndOverride() {
        contextRunner("postgresql").run(context -> assertThat(
                context.getEnvironment().getProperty(
                        "cortex.sync.academy.readiness-max-age-ms",
                        Long.class
                )
        ).isEqualTo(900_000L));

        contextRunner("postgresql")
                .withPropertyValues(
                        "CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS=420000"
                )
                .run(context -> assertThat(
                        context.getEnvironment().getProperty(
                                "cortex.sync.academy.readiness-max-age-ms",
                                Long.class
                        )
                ).isEqualTo(420_000L));
    }

    @Test
    void resolvesLocalRuntimeThroughTheSamePostgresqlReleaseGuards() {
        contextRunner("local,postgresql")
                .withPropertyValues("CORTEX_POSTGRES_RUNTIME_READY=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getActiveProfiles())
                            .contains("local", "postgresql", "postgresql-common");
                    assertResolved(context, "servlet", false, true, true);
                });
    }

    @Test
    void rejectsResolvedV47OverrideInTheEnvironmentOnlyGuardBeforeDatabaseWork() {
        contextRunner("postgresql-activation")
                .withPropertyValues("cortex.postgresql.required-schema-version=47")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty(
                            "cortex.postgresql.required-schema-version"
                    )).isEqualTo("47");

                    assertThatThrownBy(() -> new PostgresqlModeConfigurationGuard(
                            context.getEnvironment()
                    ).verifyConfiguration())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("required-schema-version")
                            .hasMessageContaining("70");
                });
    }

    private void withProfile(
            String profile,
            Consumer<ConfigurableApplicationContext> assertions
    ) {
        contextRunner(profile).run(context -> {
            assertThat(context).hasNotFailed();
            assertions.accept(context);
        });
    }

    private ApplicationContextRunner contextRunner(String profile) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.profiles.active=" + profile,
                        "CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/"
                                + "Sta" + "vias" + "Cortex"
                );
    }

    private void assertResolved(
            ConfigurableApplicationContext context,
            String webApplicationType,
            boolean flywayEnabled,
            boolean schemaReadiness,
            boolean runtimeReady
    ) {
        assertThat(context.getEnvironment().getActiveProfiles())
                .contains("postgresql-common");
        assertThat(context.getEnvironment().getProperty("spring.main.web-application-type"))
                .isEqualTo(webApplicationType);
        assertThat(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class))
                .isEqualTo(flywayEnabled);
        assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                .startsWith("jdbc:postgresql:")
                .contains("/StaviasCortex");
        assertThat(context.getEnvironment().getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("org.postgresql.Driver");
        assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.schema"))
                .isEqualTo("public");
        assertThat(context.getEnvironment().getProperty(
                "spring.datasource.hikari.data-source-properties.sslfactory"
        )).isEqualTo("org.postgresql.ssl.DefaultJavaSSLFactory");
        assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                .isEqualTo("classpath:db/migration-postgresql");
        if (flywayEnabled) {
            assertThat(context.getEnvironment().getProperty("spring.flyway.default-schema"))
                    .isEqualTo("public");
            assertThat(context.getEnvironment().getProperty("spring.flyway.schemas"))
                    .isEqualTo("public");
            assertThat(context.getEnvironment().getProperty(
                    "spring.flyway.create-schemas",
                    Boolean.class
            )).isFalse();
        }
        assertThat(context.getEnvironment().getProperty(
                "cortex.postgresql.schema-readiness.enabled",
                Boolean.class
        )).isEqualTo(schemaReadiness);
        assertThat(context.getEnvironment().getProperty(
                "cortex.postgresql.runtime-ready",
                Boolean.class
        )).isEqualTo(runtimeReady);
    }
}
