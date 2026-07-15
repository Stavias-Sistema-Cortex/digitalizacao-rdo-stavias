package com.projeto.cortex.mensagens;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MessageAttachmentConfigurationTest {

    @Test
    void multipartBoundaryRateLimitAndPrivateStorageAreConfigurable()
            throws Exception {
        Path workingDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath();
        Path application = workingDirectory.resolve(
                "src/main/resources/application.yml"
        );
        Path env = workingDirectory.resolve("../../.env.example").normalize();

        assertThat(application).exists();
        assertThat(env).exists();
        String yaml = Files.readString(application);
        String environment = Files.readString(env);

        assertThat(yaml)
                .contains("CORTEX_ATTACHMENT_MULTIPART_MAX_FILE_SIZE")
                .contains("CORTEX_ATTACHMENT_MULTIPART_MAX_REQUEST_SIZE")
                .contains("CORTEX_ATTACHMENT_RATE_LIMIT_UPLOADS_PER_MINUTE")
                .contains("CORTEX_ATTACHMENT_ALLOWED_MIME_TYPES");
        assertThat(environment)
                .contains("CORTEX_ATTACHMENT_STORAGE_PATH=")
                .contains("CORTEX_ATTACHMENT_RATE_LIMIT_UPLOADS_PER_MINUTE=")
                .contains("CORTEX_ATTACHMENT_ALLOWED_MIME_TYPES=");
    }
}
