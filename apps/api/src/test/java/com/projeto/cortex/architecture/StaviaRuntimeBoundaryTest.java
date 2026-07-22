package com.projeto.cortex.architecture;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StaviaRuntimeBoundaryTest {

    private static final String ASSISTANT_PACKAGE =
            "com.projeto.cortex.intelligence." + "stavia";
    private static final String ASSISTANT_ROUTE = "/api/" + "stavia";
    private static final String ASSISTANT_QUERY_CONTROLLER =
            "Stavia" + "QueryController";
    private static final String ASSISTANT_CONFIG =
            "cortex:" + System.lineSeparator() + "  " + "stavia:";

    @Test
    void executableBackendContainsNoAssistantRuntime() throws IOException {
        Path module = moduleRoot();

        assertThat(readTree(module.resolve("src/main/java")))
                .doesNotContain(
                        ASSISTANT_PACKAGE,
                        ASSISTANT_ROUTE,
                        ASSISTANT_QUERY_CONTROLLER
                );
        assertThat(readTree(module.resolve("src/main/resources")))
                .doesNotContain(ASSISTANT_CONFIG);
        assertThat(readTree(module.resolve("src/test/java")))
                .doesNotContain(
                        ASSISTANT_PACKAGE,
                        ASSISTANT_ROUTE,
                        ASSISTANT_QUERY_CONTROLLER
                );
        assertThat(hasRegularFiles(module.resolve(
                "target/classes/com/projeto/cortex/intelligence/" + "stavia"
        ))).isFalse();
    }

    private Path moduleRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(current.resolve("src/main/java"))) {
            return current;
        }
        return current.resolve("apps/api");
    }

    private String readTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(this::read)
                    .collect(joining(System.lineSeparator()));
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private boolean hasRegularFiles(Path root) throws IOException {
        if (Files.notExists(root)) {
            return false;
        }
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(Files::isRegularFile);
        }
    }
}
