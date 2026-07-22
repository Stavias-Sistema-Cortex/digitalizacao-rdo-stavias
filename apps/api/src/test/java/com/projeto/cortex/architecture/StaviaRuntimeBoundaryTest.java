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

    /**
     * Immutable compatibility inputs. V18/V22 are the already-applied MySQL
     * history; V44 and its two baseline contracts prove the frozen PostgreSQL
     * target-44 schema. Retirement belongs exclusively in a forward migration.
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
            "frozen V44 table inventory",
            Path.of("apps/api/src/test/java/com/projeto/cortex/config/"
                    + "PostgresqlBaselineResourceContractTest.java"),
            "V44-only structural contract"
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
        List<Path> surfaces = List.of(
                repository.resolve("apps/api/src/main"),
                repository.resolve("apps/api/src/test"),
                repository.resolve("apps/api/pom.xml"),
                repository.resolve(".env.example"),
                repository.resolve("compose.local.yml"),
                repository.resolve("compose.production.example.yml"),
                repository.resolve("scripts")
        );

        List<String> violations = new ArrayList<>();
        for (Path surface : surfaces) {
            for (Path file : filesUnder(surface)) {
                Path relative = repository.relativize(file);
                if (relative.equals(THIS_TEST) || HISTORICAL_ALLOWLIST.containsKey(relative)) {
                    continue;
                }
                inspect(relative.toString(), Files.readAllBytes(file), violations);
            }
        }

        assertThat(violations)
                .as("assistant paths/content outside archive and the documented immutable allowlist")
                .isEmpty();
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
