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
                            .hasMessageContaining("49");
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
        assertThat(context.getEnvironment().getProperty("spring.flyway.locations"))
                .isEqualTo("classpath:db/migration-postgresql");
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
