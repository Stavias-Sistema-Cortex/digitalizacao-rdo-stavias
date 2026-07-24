package com.projeto.cortex.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMemoryCursorStartupContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("../..");
    private static final Path PREFLIGHT = REPOSITORY_ROOT.resolve(
            "scripts/dev/operational-memory-cursor-preflight.sh"
    );
    @TempDir
    Path tempDir;

    @Test
    void localComposeMountsTheMemoryCursorKeyWithoutAnInlineDefault() throws Exception {
        String compose = Files.readString(
                REPOSITORY_ROOT.resolve("compose.local.yml")
        );

        assertThat(compose).contains(
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID: "
                        + "${CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID:",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE: "
                        + "/run/secrets/cortex_memory_cursor_hmac",
                "- cortex_memory_cursor_hmac",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE:"
                        + "?Point to the local memory cursor HMAC secret file"
        ).doesNotContain(
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY: ${"
        );
    }

    @Test
    void runApiInvokesTheMemoryCursorPreflightBeforeStartingMaven()
            throws Exception {
        String runApi = Files.readString(
                REPOSITORY_ROOT.resolve("scripts/dev/run-api.sh")
        );

        assertThat(runApi)
                .contains("source \"$ROOT_DIR/scripts/dev/"
                        + "operational-memory-cursor-preflight.sh\"")
                .contains("cortex_preflight_operational_memory_cursor_hmac");
        assertThat(runApi.indexOf(
                "cortex_preflight_operational_memory_cursor_hmac"
        )).isLessThan(runApi.indexOf("./mvnw spring-boot:run"));
    }

    @Test
    void preflightRejectsCompetingCurrentKeySources() throws Exception {
        Path keyFile = writeSecret("current-key", "f".repeat(32));

        Result result = runPreflight(Map.of(
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID", "local-current",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE", keyFile.toString(),
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY", "i".repeat(32)
        ));

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains(
                "arquivo ou valor inline, nunca ambos"
        );
    }

    @Test
    void preflightRejectsMaterialShorterThanRuntimeConfiguration()
            throws Exception {
        Result result = runPreflight(Map.of(
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID", "local-current",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY", "too-short"
        ));

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("pelo menos 32 caracteres");
    }

    @Test
    void preflightAcceptsAValidCurrentAndPreviousKeyring() throws Exception {
        Path previousFile = writeSecret("previous-key", "p".repeat(32));

        Result result = runPreflight(Map.of(
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID", "local-current",
                "CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY", "c".repeat(32),
                "CORTEX_MEMORY_CURSOR_HMAC_PREVIOUS_KEY_ID", "local-previous",
                "CORTEX_MEMORY_CURSOR_HMAC_PREVIOUS_KEY_FILE",
                previousFile.toString()
        ));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isBlank();
    }

    private Path writeSecret(String filename, String material) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, material, StandardCharsets.UTF_8);
        return file;
    }

    private Result runPreflight(Map<String, String> variables) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                "source \"$1\"; cortex_preflight_operational_memory_cursor_hmac",
                "memory-cursor-preflight",
                PREFLIGHT.toAbsolutePath().normalize().toString()
        );
        Map<String, String> environment = processBuilder.environment();
        environment.keySet().removeIf(key ->
                key.startsWith("CORTEX_MEMORY_CURSOR_HMAC_")
        );
        environment.putAll(variables);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        boolean finished = process.waitFor(
                Duration.ofSeconds(5).toMillis(),
                TimeUnit.MILLISECONDS
        );
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("Memory cursor preflight timed out.");
        }
        String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        return new Result(process.exitValue(), output);
    }

    private record Result(int exitCode, String output) {
    }
}
