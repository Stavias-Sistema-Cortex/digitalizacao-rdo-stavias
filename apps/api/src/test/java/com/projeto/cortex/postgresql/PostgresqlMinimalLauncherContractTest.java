package com.projeto.cortex.postgresql;

import com.projeto.cortex.auth.identity.AuthIdentityProvisioningRunner;
import com.projeto.cortex.auth.identity.AuthIdentityProvisioningWebServerGuard;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlMinimalLauncherContractTest {

    private static final Path API_ROOT = Path.of(".");
    private static final Path REPOSITORY_ROOT = Path.of("../..");
    private static final Path MIGRATION_PROFILE = API_ROOT.resolve(
            "src/main/resources/application-postgresql-migrate.yml"
    );
    private static final Path POSTGRESQL_COMMON_PROFILE = API_ROOT.resolve(
            "src/main/resources/application-postgresql-common.yml"
    );
    private static final Path MIGRATION_SCRIPT = REPOSITORY_ROOT.resolve(
            "scripts/dev/migrate-postgres-cortex.sh"
    );
    private static final Path POSTGRES_COMMON_SCRIPT = REPOSITORY_ROOT.resolve(
            "scripts/dev/postgres-cortex-common.sh"
    );
    private static final Path POM = API_ROOT.resolve("pom.xml");

    @Test
    void launchersRemainIsolatedFromTheFullCortexComponentScan() throws IOException {
        for (Path launcher : launchers()) {
            assertThat(Files.isRegularFile(launcher)).isTrue();
            String source = Files.readString(launcher);

            assertThat(source).contains(
                    "@SpringBootConfiguration",
                    "@EnableAutoConfiguration",
                    "PostgresqlModeConfigurationGuard.class"
            );
            assertThat(source).doesNotContain(
                    "@SpringBootApplication",
                    "CortexApplication",
                    "@ComponentScan",
                    "com.projeto.cortex.CortexApplication"
            );
        }

        String migration = Files.readString(launchers().getFirst());
        assertThat(migration).contains("WebApplicationType.NONE");
        assertThat(migration).contains(
                "HibernateJpaAutoConfiguration.class",
                "JpaRepositoriesAutoConfiguration.class"
        );

        String bootstrap = Files.readString(launchers().get(1));
        assertThat(bootstrap).contains(
                "@EnableConfigurationProperties(BootstrapAdminProperties.class)",
                "PostgresqlSchemaReadinessGuard.class",
                "PostgresqlBootstrapProfileGuard.class",
                "CpfLookupDigestConfiguration.class",
                "AcademySourceAdapter.class",
                "PostgresqlInitialAlfaBootstrapRunner.class"
        ).doesNotContain(
                "AuthIdentityProvisioningRunner",
                "AuthIdentityProvisioningWebServerGuard",
                "CortexOperationalMemoryService",
                "ColaboradorImportService",
                "WebAuthn",
                "CortexApplication",
                "@ComponentScan"
        );

        String activation = Files.readString(launchers().get(2));
        assertThat(activation).contains(
                "PostgresqlSchemaReadinessGuard.class",
                "PostgresqlActivationReadiness.class",
                "LocalCorsConfiguration.class",
                "HealthController.class",
                "ReadinessController.class",
                "AuthController.class",
                "PostgresqlActivationSessionProfileResolver.class",
                "AuthFilterConfiguration.class",
                "PostgresqlActivationGateConfiguration.class",
                "PostgresqlEmailOtpIdentityLookup.class",
                "PostgresqlEmailOtpChallengeRepository.class",
                "PostgresqlAuthSessionRepository.class",
                "PostgresqlRateLimitBucketRepository.class",
                "OtpDeliveryConfiguration.class",
                "EmailConfiguration.class",
                "FakeEmailGateway.class"
        ).doesNotContain(
                "CurrentUserService",
                "AuthService",
                "AuthIdentityRepository",
                "Mysql",
                "WebAuthnConfiguration",
                "WebAuthnRateLimiter",
                "Storage",
                "Sync",
                "CortexApplication",
                "@ComponentScan"
        );
    }

    @Test
    void migrationCommandUsesOnlyTheSafeNonWebPostgresqlPath() throws IOException {
        assertThat(Files.isRegularFile(MIGRATION_SCRIPT)).isTrue();
        assertThat(Files.isExecutable(MIGRATION_SCRIPT)).isTrue();
        assertThat(Files.isRegularFile(POSTGRES_COMMON_SCRIPT)).isTrue();
        String script = Files.readString(MIGRATION_SCRIPT);
        String commonGuards = Files.readString(POSTGRES_COMMON_SCRIPT);
        String migrationProfile = Files.readString(MIGRATION_PROFILE);
        String commonProfile = Files.readString(POSTGRESQL_COMMON_PROFILE);
        String effectiveCommand = script + "\n" + commonGuards + "\n"
                + migrationProfile + "\n" + commonProfile;

        assertThat(script).contains(
                "set -euo pipefail",
                "source \"${SCRIPT_DIR}/postgres-cortex-common.sh\"",
                "cortex_require_postgres_url",
                "cortex_prepare_postgres_password",
                "cortex_verify_target_database",
                "exec ./mvnw",
                "-Dspring-boot.run.main-class=com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication",
                "-Dspring-boot.run.profiles=postgresql-migrate",
                "-DskipTests",
                "spring-boot:run"
        );
        assertThat(effectiveCommand).contains(
                "CORTEX_POSTGRES_URL must target jdbc:postgresql://HOST[:PORT]/${database_name}",
                "SELECT current_database()",
                "CORTEX_POSTGRES_PASSWORD_FILE",
                "classpath:db/migration-postgresql",
                "baseline-on-migrate: false",
                "clean-disabled: true"
        );
        assertThat(script).doesNotContain(
                "set -x",
                "run-api.sh",
                "CortexApplication",
                "mysql",
                "MySQL",
                "CORTEX_DB_URL",
                "CORTEX_POSTGRES_HOST",
                "CORTEX_POSTGRES_PORT",
                "echo \"${PGPASSWORD}",
                "echo \"${CORTEX_POSTGRES_PASSWORD}"
        );
    }

    @Test
    void mavenMainClassCanBeOverriddenByTheDedicatedPostgresqlLaunchers()
            throws IOException {
        String pom = Files.readString(POM);

        assertThat(pom).contains(
                "<spring-boot.run.main-class>com.projeto.cortex.CortexApplication</spring-boot.run.main-class>",
                "<mainClass>${spring-boot.run.main-class}</mainClass>"
        );
    }

    @Test
    void failsafeIsOptInAndDoesNotGloballySelectASpringLauncher() throws IOException {
        String pom = Files.readString(POM);

        assertThat(pom).contains(
                "<id>postgresql-it</id>",
                "<artifactId>maven-failsafe-plugin</artifactId>",
                "-javaagent:${settings.localRepository}/net/bytebuddy/byte-buddy-agent/",
                "-Dnet.bytebuddy.experimental=true</argLine>",
                "<include>**/*IT.java</include>",
                "<goal>integration-test</goal>",
                "<goal>verify</goal>"
        );
        String failsafeProfile = pom.substring(pom.indexOf("<id>postgresql-it</id>"));
        assertThat(failsafeProfile).doesNotContain(
                "spring.profiles.active",
                "spring-boot.run.main-class",
                "<mainClass>"
        );
    }

    @Test
    void genericMysqlEraProvisionersCannotStartInAnyPostgresqlMode() {
        assertThat(AuthIdentityProvisioningRunner.class.getAnnotation(Profile.class).value())
                .containsExactly("!postgresql-common");
        assertThat(AuthIdentityProvisioningWebServerGuard.class.getAnnotation(Profile.class).value())
                .containsExactly("!postgresql-common");
    }

    private static List<Path> launchers() {
        Path sourceRoot = API_ROOT.resolve("src/main/java/com/projeto/cortex/postgresql");
        return List.of(
                sourceRoot.resolve("migrate/PostgresqlMigrationApplication.java"),
                sourceRoot.resolve("bootstrap/PostgresqlBootstrapApplication.java"),
                sourceRoot.resolve("activation/PostgresqlActivationApplication.java")
        );
    }
}
