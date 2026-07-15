package com.projeto.cortex.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMysqlComposeConfigurationTest {

    @Test
    void localMysqlShouldUseTheCollationExpectedByLegacyMigrations() throws Exception {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path compose = workingDirectory.resolve("compose.local.yml");
        if (Files.notExists(compose)) {
            compose = workingDirectory.resolve("../..").normalize().resolve("compose.local.yml");
        }

        assertThat(compose).exists();
        String yaml = Files.readString(compose);

        assertThat(yaml).contains("--character-set-server=utf8mb4");
        assertThat(yaml).contains("--collation-server=utf8mb4_unicode_ci");
    }
}
