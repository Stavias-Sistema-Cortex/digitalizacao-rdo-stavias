package com.projeto.cortex.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StaviaRuntimeBoundaryTest {

    private static final String ASSISTANT_NAME = "Stav" + "IA";
    private static final Pattern ASSISTANT_REFERENCE = Pattern.compile(
            "(?i)" + ASSISTANT_NAME
    );
    private static final Pattern APPROVED_REFERENCE = exactReferencePattern(
            "Stavias Sistema Cortex API",
            "STAVIAS"
    );
    private static final String PACKAGED_CLASSES_PREFIX =
            "cortex-api-0.0.1-SNAPSHOT.jar!/BOOT-INF/classes/";
    private static final List<ScopedReference> SCOPED_COMPATIBILITY_REFERENCES = List.of(
            sourceReference(
                    "apps/api/src/main/java/com/projeto/cortex/intelligence/PdorEngine.java",
                    "STAVIAS_HISTORY",
                    "AssumptionSource.STAVIAS_HISTORY",
                    "AssumptionSource.STAVIAS_HISTORY",
                    "STAVIAS_HISTORY,"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/intelligence/"
                            + "PdorMonteCarloPotentialValidationTest.java",
                    "STAVIAS_HISTORY",
                    "PdorEngine.AssumptionSource.STAVIAS_HISTORY",
                    "PdorEngine.AssumptionSource.STAVIAS_HISTORY"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/intelligence/PdorEngineTest.java",
                    "STAVIAS_HISTORY",
                    "PdorEngine.AssumptionSource.STAVIAS_HISTORY",
                    "PdorEngine.AssumptionSource.STAVIAS_HISTORY"),
            sourceReference(
                    ".env.postgresql.example",
                    "StaviasCortex",
                    "CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/StaviasCortex"),
            sourceReference(
                    "scripts/dev/migrate-postgres-cortex.sh",
                    "StaviasCortex",
                    "# Explicit transition 1/4: install V44 in an already provisioned "
                            + "StaviasCortex."),
            sourceReference(
                    "scripts/dev/postgres-cortex-common.sh",
                    "StaviasCortex",
                    "printf '%s' 'StaviasCortex'"),
            sourceReference(
                    "apps/api/src/main/resources/application-postgresql-common.yml",
                    "StaviasCortex",
                    "url: ${CORTEX_POSTGRES_URL:jdbc:postgresql://127.0.0.1:5432/"
                            + "StaviasCortex}"),
            sourceReference(
                    "apps/api/src/main/java/com/projeto/cortex/config/"
                            + "PostgresqlModeConfigurationGuard.java",
                    "StaviasCortex",
                    "endsWith(\"/StaviasCortex\")",
                    "PostgreSQL canônico StaviasCortex."),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/postgresql/"
                            + "PostgresqlV44MigrationIT.java",
                    "StaviasCortex",
                    ".withDatabaseName(\"StaviasCortex\"))"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/config/"
                            + "PostgresqlProfileModesContractTest.java",
                    "StaviasCortex",
                    "${CORTEX_POSTGRES_URL:jdbc:postgresql://127.0.0.1:5432/"
                            + "StaviasCortex}"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/auth/bootstrap/"
                            + "PostgresqlInitialAlfaBootstrapRepositoryIT.java",
                    "StaviasCortex",
                    ".withDatabaseName(\"StaviasCortex\")"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/config/"
                            + "PostgresqlProvisionerContractTest.java",
                    "StaviasCortex",
                    "commonGuards.contains(\"StaviasCortex\")",
                    "CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/StaviasCortex"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/auth/postgresql/"
                            + "PostgresqlAuthPersistenceTestSupport.java",
                    "StaviasCortex",
                    ".withDatabaseName(\"StaviasCortex\")"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/config/"
                            + "PostgresqlEffectiveConfigurationTest.java",
                    "StaviasCortex",
                    ".contains(\"/StaviasCortex\")"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/config/"
                            + "PostgresqlModeConfigurationGuardTest.java",
                    "StaviasCortex",
                    ".hasMessageContaining(\"StaviasCortex\")",
                    ".hasMessageContaining(\"StaviasCortex\")",
                    "jdbc:postgresql://127.0.0.1:5432/StaviasCortex"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/ontology/graph/"
                            + "PostgresqlOntologyGraphRepositoryIT.java",
                    "StaviasCortex",
                    ".withDatabaseName(\"StaviasCortex\")"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/ontology/graph/"
                            + "PostgresqlOntologyGraphQueryServiceIT.java",
                    "StaviasCortex",
                    ".withDatabaseName(\"StaviasCortex\"))",
                    ".withDatabaseName(\"StaviasCortex\"))",
                    ".withDatabaseName(\"StaviasCortex\"))",
                    ".withDatabaseName(\"StaviasCortex\"))"),
            sourceReference(
                    "apps/api/src/main/java/com/projeto/cortex/colaboradores/"
                            + "AcademyCollaboratorIdentity.java",
                    "dbstavias_acad",
                    "SOURCE_NAMESPACE = \"dbstavias_acad:usuarios:\""),
            sourceReference(
                    "apps/api/src/main/java/com/projeto/cortex/colaboradores/"
                            + "ColaboradorImportService.java",
                    "dbstavias_acad",
                    "BANCO_ORIGEM = \"dbstavias_acad\""),
            sourceReference(
                    "apps/api/src/main/java/com/projeto/cortex/integracoes/"
                            + "IntegracaoAdminService.java",
                    "dbstavias_acad",
                    "\"dbstavias_acad\","),
            sourceReference(
                    "apps/api/src/main/java/com/projeto/cortex/auth/bootstrap/"
                            + "PostgresqlInitialAlfaBootstrapRepository.java",
                    "dbstavias_acad",
                    "ACADEMY_DATABASE = \"dbstavias_acad\""),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/colaboradores/"
                            + "AcademyCollaboratorIdentityTest.java",
                    "dbstavias_acad",
                    "\"dbstavias_acad:usuarios:\""),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/colaboradores/"
                            + "ColaboradorImportServiceTest.java",
                    "dbstavias_acad",
                    "eq(\"dbstavias_acad\")",
                    "eq(\"dbstavias_acad\")"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/auth/bootstrap/"
                            + "PostgresqlInitialAlfaBootstrapRepositoryIT.java",
                    "dbstavias_acad",
                    "banco_origem = 'dbstavias_acad'"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/pdor/"
                            + "ColaboradorImportServiceMysqlIntegrationTest.java",
                    "dbstavias_acad",
                    "banco_origem = 'dbstavias_acad'",
                    "banco_origem = 'dbstavias_acad'"),
            sourceReference(
                    "apps/api/src/main/java/com/projeto/cortex/assets/AssetImportService.java",
                    "dbstavias_zld",
                    "SOURCE_DATABASE = \"dbstavias_zld\"",
                    "Failed to import assets from dbstavias_zld.ativos"),
            sourceReference(
                    "apps/api/src/main/java/com/projeto/cortex/integracoes/"
                            + "IntegracaoAdminService.java",
                    "dbstavias_zld",
                    "\"dbstavias_zld\","),
            sourceReference(
                    "compose.production.example.yml",
                    "Stavias Córtex",
                    "CORTEX_AUTH_WEBAUTHN_RP_NAME:-Stavias Córtex}"),
            sourceReference(
                    "compose.production.example.yml",
                    "Stavias From",
                    "Set the authenticated Stavias From mailbox"),
            sourceReference(
                    "scripts/smoke-deploy.sh",
                    "Córtex Stavias",
                    "\"name\":\"Córtex Stavias\""),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/pdor/"
                            + "FinanceEmailChargesMigrationMysqlIntegrationTest.java",
                    "Financeiro Stavias",
                    "'FINANCEIRO', 'Financeiro Stavias',"),
            sourceReference(
                    "apps/api/src/test/java/com/projeto/cortex/pdor/"
                            + "FinanceChargeDeliveryMysqlIntegrationTest.java",
                    "Financeiro Stavias",
                    "'FINANCEIRO', 'Financeiro Stavias',"),
            compiledReference(
                    "target/classes/application-postgresql-common.yml",
                    "StaviasCortex",
                    1,
                    "url: ${CORTEX_POSTGRES_URL:jdbc:postgresql://127.0.0.1:5432/"
                            + "StaviasCortex}"),
            compiledReference(
                    "target/classes/com/projeto/cortex/intelligence/PdorEngine.class",
                    "STAVIAS_HISTORY", 1, "PdorEngine.java"),
            compiledReference(
                    "target/classes/com/projeto/cortex/intelligence/"
                            + "PdorEngine$AssumptionSource.class",
                    "STAVIAS_HISTORY", 1, "PdorEngine.java"),
            compiledReference(
                    "target/classes/com/projeto/cortex/config/"
                            + "PostgresqlModeConfigurationGuard.class",
                    "StaviasCortex", 2, "PostgresqlModeConfigurationGuard.java"),
            compiledReference(
                    "target/classes/com/projeto/cortex/colaboradores/"
                            + "AcademyCollaboratorIdentity.class",
                    "dbstavias_acad", 2, "AcademyCollaboratorIdentity.java"),
            compiledReference(
                    "target/classes/com/projeto/cortex/colaboradores/"
                            + "ColaboradorImportService.class",
                    "dbstavias_acad", 2, "ColaboradorImportService.java"),
            compiledReference(
                    "target/classes/com/projeto/cortex/integracoes/"
                            + "IntegracaoAdminService.class",
                    "dbstavias_acad", 1, "IntegracaoAdminService.java"),
            compiledReference(
                    "target/classes/com/projeto/cortex/auth/bootstrap/"
                            + "PostgresqlInitialAlfaBootstrapRepository.class",
                    "dbstavias_acad", 1, "PostgresqlInitialAlfaBootstrapRepository.java"),
            compiledReference(
                    "target/classes/com/projeto/cortex/assets/AssetImportService.class",
                    "dbstavias_zld", 3, "AssetImportService.java"),
            compiledReference(
                    "target/classes/com/projeto/cortex/integracoes/"
                            + "IntegracaoAdminService.class",
                    "dbstavias_zld", 1, "IntegracaoAdminService.java")
    );
    private static final Path THIS_TEST = Path.of(
            "apps/api/src/test/java/com/projeto/cortex/architecture/"
                    + "Sta" + "via" + "RuntimeBoundaryTest.java"
    );
    private static final Path POSTGRESQL_BASELINE_CONTRACT = Path.of(
            "apps/api/src/test/java/com/projeto/cortex/config/"
                    + "PostgresqlBaselineResourceContractTest.java"
    );
    private static final String POSTGRESQL_BASELINE_HISTORICAL_TOKEN =
            "stavia_contexto_obra";
    private static final int POSTGRESQL_BASELINE_HISTORICAL_LINE = 118;
    private static final String POSTGRESQL_BASELINE_HISTORICAL_FRAGMENT =
            "assertObjectStorageBoundary(sql, \"" + POSTGRESQL_BASELINE_HISTORICAL_TOKEN
                    + "\", \"storage_key varchar(512)\");";
    private static final Set<String> EXCLUDED_DISCOVERY_DIRECTORIES = Set.of(
            ".git", ".gradle", "archive", "build", "coverage", "dist",
            "node_modules", "target"
    );

    /**
     * Immutable file-level compatibility inputs. V18/V22 are the already-applied
     * MySQL history; V44 and its frozen inventory prove the target-44 schema.
     * Retirement belongs exclusively in the forward V45.1 migration. The active
     * V44 Java contract is scanned with the exact occurrence exception below.
     */
    private static final Map<Path, String> HISTORICAL_ALLOWLIST = Map.of(
            Path.of("apps/api/src/main/resources/db/migration/"
                    + "V18__create_stavia_worksite_context.sql"),
            "immutable MySQL migration V18",
            Path.of("apps/api/src/main/resources/db/migration/"
                    + "V22__create_stavia_operational_ontology.sql"),
            "immutable MySQL migration V22",
            Path.of("apps/api/src/main/resources/db/migration-postgresql/"
                    + "V44__postgresql_schema_baseline.sql"),
            "immutable PostgreSQL baseline V44",
            Path.of("apps/api/src/main/resources/db/migration-postgresql/"
                    + "V45_1__retire_stavia_runtime.sql"),
            "forward-only PostgreSQL retirement migration V45.1",
            Path.of("apps/api/src/test/resources/postgresql/v44-required-tables.txt"),
            "frozen V44 table inventory"
    );
    private static final Set<String> COMPILED_HISTORICAL_ALLOWLIST = Set.of(
            "db/migration/V18__create_stavia_worksite_context.sql",
            "db/migration/V22__create_stavia_operational_ontology.sql",
            "db/migration-postgresql/V44__postgresql_schema_baseline.sql",
            "db/migration-postgresql/V45_1__retire_stavia_runtime.sql"
    );

    @Test
    void activeBackendSourcesResourcesAndLaunchersContainNoAssistantRuntime()
            throws IOException {
        Path repository = repositoryRoot();
        List<String> violations = new ArrayList<>();
        for (Path file : activeSourceAndLauncherFiles(repository)) {
            Path relative = repository.relativize(file);
            inspectSourceFile(relative, Files.readAllBytes(file), violations);
        }

        assertThat(violations)
                .as("assistant paths/content outside archive and the documented immutable allowlist")
                .isEmpty();
    }

    @Test
    void discoversEveryBackendEnvironmentSurface() throws IOException {
        assertThat(activeRelativeSourceAndLauncherFiles())
                .contains(Path.of(".env.postgresql.example"));
    }

    @Test
    void discoversEveryBackendContainerSurface() throws IOException {
        assertThat(activeRelativeSourceAndLauncherFiles())
                .contains(Path.of("apps/api/Dockerfile"));
    }

    @Test
    void discoversActiveHistoricalContractWithoutExemptingItsWholeFile()
            throws IOException {
        assertThat(activeRelativeSourceAndLauncherFiles())
                .contains(POSTGRESQL_BASELINE_CONTRACT);
    }

    @Test
    void allowsOnlyTheVerifiedHistoricalOccurrenceInTheActiveBaselineContract()
            throws IOException {
        Path repository = repositoryRoot();
        List<String> violations = new ArrayList<>();

        inspectSourceFile(POSTGRESQL_BASELINE_CONTRACT,
                Files.readAllBytes(repository.resolve(POSTGRESQL_BASELINE_CONTRACT)),
                violations);

        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsAnyAdditionalAssistantOccurrenceInTheActiveBaselineContract()
            throws IOException {
        Path repository = repositoryRoot();
        byte[] current = Files.readAllBytes(repository.resolve(POSTGRESQL_BASELINE_CONTRACT));
        byte[] extra = (new String(current, StandardCharsets.UTF_8)
                + System.lineSeparator() + "// stavia_new_runtime").getBytes(StandardCharsets.UTF_8);
        List<String> violations = new ArrayList<>();

        inspectSourceFile(POSTGRESQL_BASELINE_CONTRACT, extra, violations);

        assertThat(violations)
                .containsExactly(POSTGRESQL_BASELINE_CONTRACT + " [assistant content]");
    }

    @Test
    void rejectsRetiredAssistantNamesAcrossSourceCompiledAndJarLikeSurfaces() {
        List<String> violations = new ArrayList<>();

        inspect("apps/api/src/main/java/example/StaviaSnapshotService.java",
                "class StaviaSpringContextTest {}".getBytes(StandardCharsets.UTF_8),
                violations);
        inspect("target/classes/example/StaviaSpringContextTest.class",
                "StaviaSnapshotService".getBytes(StandardCharsets.ISO_8859_1),
                violations);
        inspect("cortex-api.jar!/BOOT-INF/classes/example/StaviaSnapshotService.class",
                "StaviaSpringContextTest".getBytes(StandardCharsets.ISO_8859_1),
                violations);

        assertThat(violations).containsExactly(
                "apps/api/src/main/java/example/StaviaSnapshotService.java [assistant path]",
                "apps/api/src/main/java/example/StaviaSnapshotService.java [assistant content]",
                "target/classes/example/StaviaSpringContextTest.class [assistant path]",
                "target/classes/example/StaviaSpringContextTest.class [assistant content]",
                "cortex-api.jar!/BOOT-INF/classes/example/StaviaSnapshotService.class [assistant path]",
                "cortex-api.jar!/BOOT-INF/classes/example/StaviaSnapshotService.class [assistant content]"
        );
    }

    @Test
    void allowsExactCorporateBrandAndProductName() {
        List<String> violations = new ArrayList<>();

        inspect("branding.txt",
                "STAVIAS\nStavias Sistema Cortex API".getBytes(StandardCharsets.UTF_8),
                violations);

        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsUnapprovedCorporateBrandCaseAndBoundaryVariants() {
        for (String unapproved : List.of(
                "stavias",
                "Stavias",
                "StAvIaS",
                "StaviaS",
                "STAVias",
                "STAVIASRuntime",
                "RuntimeSTAVIAS",
                "PrefixStavias Sistema Cortex API",
                "stavias Sistema Cortex API")) {
            List<String> violations = new ArrayList<>();

            inspect("branding.txt", unapproved.getBytes(StandardCharsets.UTF_8), violations);

            assertThat(violations)
                    .as("unapproved case/boundary variant %s", unapproved)
                    .containsExactly("branding.txt [assistant content]");
        }
    }

    @ParameterizedTest(name = "rejects scoped-only reference {0}")
    @ValueSource(strings = {
            "STAVIAS_HISTORY",
            "StaviasCortex",
            "dbstavias_acad",
            "dbstavias_zld",
            "Stavias Córtex",
            "Stavias From",
            "Córtex Stavias",
            "Financeiro Stavias"
    })
    void rejectsCompatibilityReferencesOnUnauthorizedSourceClassAndJarSurfaces(
            String reference) {
        List<String> violations = new ArrayList<>();

        inspect("apps/api/src/main/java/example/CompatibilityReference.java",
                reference.getBytes(StandardCharsets.UTF_8), violations);
        inspect("target/classes/example/CompatibilityReference.class",
                reference.getBytes(StandardCharsets.ISO_8859_1), violations);
        inspect("cortex-api.jar!/BOOT-INF/classes/example/CompatibilityReference.class",
                reference.getBytes(StandardCharsets.ISO_8859_1), violations);

        assertThat(violations).containsExactly(
                "apps/api/src/main/java/example/CompatibilityReference.java [assistant content]",
                "target/classes/example/CompatibilityReference.class [assistant content]",
                "cortex-api.jar!/BOOT-INF/classes/example/CompatibilityReference.class [assistant content]"
        );
    }

    @ParameterizedTest(name = "rejects additional occurrence of {0}")
    @ValueSource(strings = {
            "STAVIAS_HISTORY|apps/api/src/main/java/com/projeto/cortex/intelligence/PdorEngine.java",
            "StaviasCortex|.env.postgresql.example",
            "dbstavias_acad|apps/api/src/main/java/com/projeto/cortex/colaboradores/AcademyCollaboratorIdentity.java",
            "dbstavias_zld|apps/api/src/main/java/com/projeto/cortex/assets/AssetImportService.java",
            "Stavias Córtex|compose.production.example.yml",
            "Stavias From|compose.production.example.yml",
            "Córtex Stavias|scripts/smoke-deploy.sh",
            "Financeiro Stavias|apps/api/src/test/java/com/projeto/cortex/pdor/FinanceEmailChargesMigrationMysqlIntegrationTest.java"
    })
    void rejectsAdditionalCompatibilityReferenceInItsEstablishedFile(String fixture)
            throws IOException {
        String[] parts = fixture.split("\\|", 2);
        String reference = parts[0];
        Path relative = Path.of(parts[1]);
        byte[] current = Files.readAllBytes(repositoryRoot().resolve(relative));
        byte[] duplicate = (new String(current, StandardCharsets.UTF_8)
                + System.lineSeparator() + reference).getBytes(StandardCharsets.UTF_8);
        List<String> violations = new ArrayList<>();

        inspectSourceFile(relative, duplicate, violations);

        assertThat(violations).containsExactly(relative + " [assistant content]");
    }

    @ParameterizedTest(name = "rejects Java identifier join {0}")
    @ValueSource(strings = {
            "STAVIAS$Runtime",
            "Runtime$STAVIAS",
            "STAVIAS$Service",
            "Stavias$Service",
            "stavias$Service",
            "sTaViAs$Service"
    })
    void rejectsDollarJoinedAssistantReferencesAcrossSourceClassAndJarSurfaces(
            String reference) {
        List<String> violations = new ArrayList<>();

        inspect("apps/api/src/main/java/example/DollarJoined.java",
                reference.getBytes(StandardCharsets.UTF_8), violations);
        inspect("target/classes/example/DollarJoined.class",
                reference.getBytes(StandardCharsets.ISO_8859_1), violations);
        inspect("cortex-api.jar!/BOOT-INF/classes/example/DollarJoined.class",
                reference.getBytes(StandardCharsets.ISO_8859_1), violations);

        assertThat(violations).containsExactly(
                "apps/api/src/main/java/example/DollarJoined.java [assistant content]",
                "target/classes/example/DollarJoined.class [assistant content]",
                "cortex-api.jar!/BOOT-INF/classes/example/DollarJoined.class [assistant content]"
        );
    }

    private static List<Path> activeRelativeSourceAndLauncherFiles() throws IOException {
        Path repository = repositoryRoot();
        return activeSourceAndLauncherFiles(repository).stream()
                .map(repository::relativize)
                .toList();
    }

    private static List<Path> activeSourceAndLauncherFiles(Path repository) throws IOException {
        try (Stream<Path> paths = Files.walk(repository)) {
            return paths.filter(Files::isRegularFile)
                    .filter(file -> {
                        Path relative = repository.relativize(file);
                        return !isExcludedDiscoveryPath(relative)
                                && isActiveSourceOrLauncherSurface(relative)
                                && !relative.equals(THIS_TEST)
                                && !HISTORICAL_ALLOWLIST.containsKey(relative);
                    })
                    .sorted()
                    .toList();
        }
    }

    private static boolean isExcludedDiscoveryPath(Path relative) {
        for (Path segment : relative) {
            if (EXCLUDED_DISCOVERY_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isActiveSourceOrLauncherSurface(Path relative) {
        String fileName = relative.getFileName().toString();
        return relative.startsWith(Path.of("apps/api/src/main"))
                || relative.startsWith(Path.of("apps/api/src/test"))
                || relative.equals(Path.of("apps/api/pom.xml"))
                || relative.startsWith(Path.of("scripts"))
                || fileName.startsWith(".env")
                || fileName.startsWith("Dockerfile")
                || fileName.startsWith("compose")
                || fileName.startsWith("docker-compose");
    }

    private static void inspectSourceFile(
            Path relative, byte[] bytes, List<String> violations) {
        if (relative.equals(POSTGRESQL_BASELINE_CONTRACT)) {
            inspectPostgresqlBaselineContract(relative, bytes, violations);
            return;
        }
        inspect(relative.toString(), bytes, violations);
    }

    private static void inspectPostgresqlBaselineContract(
            Path relative, byte[] bytes, List<String> violations) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\\R", -1);
        int tokenOccurrences = literalOccurrences(
                content, POSTGRESQL_BASELINE_HISTORICAL_TOKEN);
        boolean exactLine = lines.length >= POSTGRESQL_BASELINE_HISTORICAL_LINE
                && lines[POSTGRESQL_BASELINE_HISTORICAL_LINE - 1].trim()
                        .equals(POSTGRESQL_BASELINE_HISTORICAL_FRAGMENT);

        if (tokenOccurrences != 1 || !exactLine) {
            violations.add(relative + " [historical exception mismatch: expected exactly one \""
                    + POSTGRESQL_BASELINE_HISTORICAL_TOKEN + "\" at line "
                    + POSTGRESQL_BASELINE_HISTORICAL_LINE + " as \""
                    + POSTGRESQL_BASELINE_HISTORICAL_FRAGMENT + "\"]");
            inspect(relative.toString(), bytes, violations);
            return;
        }

        int tokenStart = content.indexOf(POSTGRESQL_BASELINE_HISTORICAL_TOKEN);
        String inspectedContent = content.substring(0, tokenStart)
                + "historical_contexto_obra"
                + content.substring(tokenStart + POSTGRESQL_BASELINE_HISTORICAL_TOKEN.length());
        inspect(relative.toString(), inspectedContent.getBytes(StandardCharsets.UTF_8), violations);
    }

    private static int literalOccurrences(String content, String token) {
        int count = 0;
        for (int index = content.indexOf(token);
                index >= 0;
                index = content.indexOf(token, index + token.length())) {
            count++;
        }
        return count;
    }

    @Test
    void compiledClassesAndPackagedJarsContainNoAssistantRuntime() throws IOException {
        Path module = moduleRoot();
        List<String> violations = new ArrayList<>();
        Path classes = module.resolve("target/classes");

        for (Path file : filesUnder(classes)) {
            String relative = classes.relativize(file).toString().replace('\\', '/');
            if (!COMPILED_HISTORICAL_ALLOWLIST.contains(relative)) {
                inspect("target/classes/" + relative, Files.readAllBytes(file), violations);
            }
        }

        Path target = module.resolve("target");
        for (Path jar : directJarFiles(target)) {
            inspectJar(jar, violations);
        }

        assertThat(violations)
                .as("compiled backend and JAR must exclude assistant code and resources")
                .isEmpty();
    }

    private static void inspect(String displayPath, byte[] bytes, List<String> violations) {
        if (containsAssistantReference(displayPath.replace('\\', '/'))) {
            violations.add(displayPath + " [assistant path]");
        }
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        String inspectedContent = removeScopedCompatibilityReferences(displayPath, content);
        if (containsAssistantReference(inspectedContent)) {
            violations.add(displayPath + " [assistant content]");
        }
    }

    private static String removeScopedCompatibilityReferences(
            String displayPath, String content) {
        String normalizedPath = normalizeCompatibilityPath(displayPath.replace('\\', '/'));
        String inspectedContent = content;
        for (ScopedReference scoped : SCOPED_COMPATIBILITY_REFERENCES) {
            if (!scoped.path().equals(normalizedPath)) {
                continue;
            }
            String encodedReference = binaryView(scoped.reference());
            String contentAtRule = inspectedContent;
            boolean exactReferenceCount = literalOccurrences(
                    contentAtRule, encodedReference) == scoped.expectedOccurrences();
            boolean exactFragments = scoped.exactFragments().stream()
                    .distinct()
                    .allMatch(fragment -> literalOccurrences(contentAtRule, binaryView(fragment))
                            == literalOccurrences(scoped.exactFragments(), fragment));
            if (exactReferenceCount && exactFragments) {
                inspectedContent = inspectedContent.replace(
                        encodedReference, "x".repeat(encodedReference.length()));
            }
        }
        return inspectedContent;
    }

    private static String normalizeCompatibilityPath(String displayPath) {
        if (displayPath.startsWith(PACKAGED_CLASSES_PREFIX)) {
            return "target/classes/" + displayPath.substring(PACKAGED_CLASSES_PREFIX.length());
        }
        return displayPath;
    }

    private static boolean containsAssistantReference(String value) {
        String inspectedValue = APPROVED_REFERENCE.matcher(value).replaceAll("");
        return ASSISTANT_REFERENCE.matcher(inspectedValue).find();
    }

    private static Pattern exactReferencePattern(String... references) {
        String alternatives = String.join("|",
                Stream.of(references)
                        .flatMap(reference -> Stream.of(
                                reference,
                                new String(reference.getBytes(StandardCharsets.UTF_8),
                                        StandardCharsets.ISO_8859_1)))
                        .distinct()
                        .map(Pattern::quote)
                        .toList());
        String identifierCharacter = "\\p{javaJavaIdentifierPart}";
        return Pattern.compile("(?<!" + identifierCharacter + ")(?:"
                + alternatives + ")(?!" + identifierCharacter + ")");
    }

    private static ScopedReference sourceReference(
            String path, String reference, String... exactFragments) {
        List<String> fragments = List.of(exactFragments);
        int expectedOccurrences = fragments.stream()
                .mapToInt(fragment -> literalOccurrences(fragment, reference))
                .sum();
        if (expectedOccurrences == 0) {
            throw new IllegalArgumentException("Source fragments must contain " + reference);
        }
        return new ScopedReference(path, reference, expectedOccurrences, fragments);
    }

    private static ScopedReference compiledReference(
            String path,
            String reference,
            int expectedOccurrences,
            String exactContextFragment) {
        return new ScopedReference(
                path, reference, expectedOccurrences, List.of(exactContextFragment));
    }

    private static int literalOccurrences(List<String> values, String expected) {
        return (int) values.stream().filter(expected::equals).count();
    }

    private static String binaryView(String value) {
        return new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    private static void inspectJar(Path jar, List<String> violations) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !isApplicationEntry(entry.getName())) {
                    continue;
                }
                String classpathEntry = stripApplicationPrefix(entry.getName());
                if (COMPILED_HISTORICAL_ALLOWLIST.contains(classpathEntry)) {
                    continue;
                }
                inspect(jar.getFileName() + "!/" + entry.getName(),
                        zip.getInputStream(entry).readAllBytes(), violations);
            }
        }
    }

    private static boolean isApplicationEntry(String entry) {
        return entry.startsWith("BOOT-INF/classes/")
                || (!entry.startsWith("BOOT-INF/") && !entry.startsWith("META-INF/"));
    }

    private static String stripApplicationPrefix(String entry) {
        String prefix = "BOOT-INF/classes/";
        return entry.startsWith(prefix) ? entry.substring(prefix.length()) : entry;
    }

    private static List<Path> filesUnder(Path root) throws IOException {
        if (Files.isRegularFile(root)) {
            return List.of(root);
        }
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static List<Path> directJarFiles(Path target) throws IOException {
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(target)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList();
        }
    }

    private static Path moduleRoot() {
        return repositoryRoot().resolve("apps/api");
    }

    private static Path repositoryRoot() {
        Path configured = Path.of(System.getProperty("basedir", ""))
                .toAbsolutePath().normalize();
        for (Path candidate = configured; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("apps/api/pom.xml"))) {
                return candidate;
            }
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && candidate.endsWith(Path.of("apps/api"))) {
                return candidate.getParent().getParent();
            }
        }
        throw new IllegalStateException("Unable to locate repository from " + configured);
    }

    private record ScopedReference(
            String path,
            String reference,
            int expectedOccurrences,
            List<String> exactFragments) {

        private ScopedReference {
            exactFragments = List.copyOf(exactFragments);
        }
    }
}
