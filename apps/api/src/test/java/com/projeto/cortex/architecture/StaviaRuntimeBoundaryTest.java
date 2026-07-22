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

class StaviaRuntimeBoundaryTest {

    private static final String ASSISTANT_NAME = "Stav" + "IA";
    private static final Pattern ASSISTANT_REFERENCE = Pattern.compile(
            "(?i)" + ASSISTANT_NAME + "(?!s)"
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
        if (ASSISTANT_REFERENCE.matcher(displayPath.replace('\\', '/')).find()) {
            violations.add(displayPath + " [assistant path]");
        }
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        if (ASSISTANT_REFERENCE.matcher(content).find()) {
            violations.add(displayPath + " [assistant content]");
        }
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
}
