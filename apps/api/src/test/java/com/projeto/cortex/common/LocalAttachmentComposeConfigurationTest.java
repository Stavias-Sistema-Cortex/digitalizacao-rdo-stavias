package com.projeto.cortex.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAttachmentComposeConfigurationTest {

    @Test
    void localApiShouldPersistProtectedAttachmentsOutsideTheContainerLayer() throws Exception {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path compose = workingDirectory.resolve("compose.local.yml");
        if (Files.notExists(compose)) {
            compose = workingDirectory.resolve("../..").normalize().resolve("compose.local.yml");
        }

        assertThat(compose).exists();
        String yaml = Files.readString(compose);

        assertThat(yaml).contains(
                "CORTEX_ATTACHMENT_STORAGE_PATH: /app/var/cortex-attachments"
        );
        assertThat(yaml).contains(
                "cortex_attachment_data:/app/var/cortex-attachments"
        );
        assertThat(yaml).contains("cortex_attachment_data:");
    }
}
