package com.projeto.cortex.config;

import com.projeto.cortex.auth.AuthReadinessIndicator;
import com.projeto.cortex.auth.offline.OfflineGrantPublicKeyFingerprint;
import com.projeto.cortex.common.ReadinessController;
import com.projeto.cortex.common.RuntimeInstanceFingerprint;
import com.projeto.cortex.common.RuntimeReadiness;
import com.projeto.cortex.common.RuntimeRevision;
import com.projeto.cortex.storage.ObjectStorageReadiness;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlProfileModesContractTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void declaresTheExactPublicProfileTopology() throws IOException {
        String application = Files.readString(RESOURCES.resolve("application.yml"));

        assertThat(application).contains(
                "postgresql: [postgresql-common]",
                "postgresql-migrate: [postgresql-common]",
                "postgresql-bootstrap: [postgresql-common]",
                "postgresql-activation: [postgresql-common]"
        );
    }

    @Test
    void isolatesDatasourceAndMigrationSettingsInTheCommonProfile() throws IOException {
        String common = profile("postgresql-common");
        String application = Files.readString(RESOURCES.resolve("application.yml"));

        assertThat(common).contains(
                "on-profile: postgresql-common",
                "${CORTEX_POSTGRES_URL}",
                "${CORTEX_POSTGRES_USER:joaolucas}",
                "${CORTEX_POSTGRES_PASSWORD:}",
                "schema: public",
                "classpath:db/migration-postgresql",
                "required-schema-version: 64"
        );
        assertThat(common).doesNotContain("schema-readiness:", "runtime-ready:");
        assertThat(common).doesNotContain("${CORTEX_POSTGRES_URL:");
        assertThat(application).contains(
                "schema-readiness:\n      enabled: false",
                "runtime-ready: false"
        );
        assertThat(common).doesNotContain("CORTEX_DB_URL", "Supabase", "supabase");
    }

    @Test
    void migrationModeIsNonWebAndOwnsFlywayWithoutReadinessGuards() throws IOException {
        String migration = profile("postgresql-migrate");

        assertThat(migration).contains(
                "on-profile: postgresql-migrate",
                "web-application-type: none",
                "enabled: true",
                "default-schema: public",
                "schemas: public",
                "create-schemas: false",
                "baseline-on-migrate: false",
                "clean-disabled: true",
                "schema-readiness:",
                "runtime-ready: false"
        );
        assertThat(migration).contains("schema-readiness:\n      enabled: false");
    }

    @Test
    void bootstrapModeIsNonWebAndRequiresOnlyTheInstalledSchema() throws IOException {
        String bootstrap = profile("postgresql-bootstrap");

        assertThat(bootstrap).contains(
                "on-profile: postgresql-bootstrap",
                "web-application-type: none",
                "flyway:\n    enabled: false",
                "schema-readiness:\n      enabled: true",
                "runtime-ready: false"
        );
    }

    @Test
    void activationModeIsServletAndNeverEnablesNormalRuntime() throws IOException {
        String activation = profile("postgresql-activation");

        assertThat(activation).contains(
                "on-profile: postgresql-activation",
                "web-application-type: servlet",
                "flyway:\n    enabled: false",
                "schema-readiness:\n      enabled: true",
                "runtime-ready: false"
        );
    }

    @Test
    void normalRuntimeIsServletSchemaCheckedAndOwnerGated() throws IOException {
        String runtime = profile("postgresql");

        assertThat(runtime).contains(
                "on-profile: postgresql",
                "web-application-type: servlet",
                "flyway:\n    enabled: false",
                "schema-readiness:\n      enabled: true",
                "runtime-ready: ${CORTEX_POSTGRES_RUNTIME_READY:false}"
        );
    }

    @Test
    void separatesMysqlReadinessFromTheSharedControllerContract() {
        Profile mysqlOnly = AuthReadinessIndicator.class.getAnnotation(Profile.class);

        assertThat(mysqlOnly.value()).containsExactly("!postgresql-common");
        assertThat(ReadinessController.class.getDeclaredConstructors())
                .singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterTypes())
                        .containsExactly(
                                RuntimeReadiness.class,
                                RuntimeRevision.class,
                                RuntimeInstanceFingerprint.class,
                                OfflineGrantPublicKeyFingerprint.class,
                                ObjectStorageReadiness.class
                        ));
    }

    private String profile(String name) throws IOException {
        return Files.readString(RESOURCES.resolve("application-" + name + ".yml"));
    }
}
