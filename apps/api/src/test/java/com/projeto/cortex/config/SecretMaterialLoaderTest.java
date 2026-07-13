package com.projeto.cortex.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretMaterialLoaderTest {

    private static final String TEST_SECRET =
            "test-only-cpf-hmac-secret-material-0001";

    @TempDir
    Path tempDir;

    @Test
    void readsMountedSecretAndRemovesOnlySurroundingWhitespace() throws Exception {
        Path keyFile = tempDir.resolve("cpf-hmac-key");
        Files.writeString(keyFile, "  " + TEST_SECRET + "\n");

        byte[] material = SecretMaterialLoader.required(
                keyFile,
                null,
                "CPF HMAC"
        );

        assertThat(new String(material, StandardCharsets.UTF_8))
                .isEqualTo(TEST_SECRET);
    }

    @Test
    void acceptsInlineSecretWhenNoFileWasConfigured() {
        byte[] material = SecretMaterialLoader.required(
                null,
                TEST_SECRET,
                "CPF HMAC"
        );

        assertThat(new String(material, StandardCharsets.UTF_8))
                .isEqualTo(TEST_SECRET);
    }

    @Test
    void refusesAmbiguousFileAndInlineSources() throws Exception {
        Path keyFile = tempDir.resolve("cpf-hmac-key");
        Files.writeString(keyFile, TEST_SECRET);

        assertThatThrownBy(() -> SecretMaterialLoader.required(
                keyFile,
                TEST_SECRET,
                "CPF HMAC"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("arquivo ou valor inline");
    }

    @Test
    void refusesMissingOrShortSecretWithoutDisclosingItsValue() {
        assertThatThrownBy(() -> SecretMaterialLoader.required(
                tempDir.resolve("missing"),
                null,
                "CPF HMAC"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(TEST_SECRET);

        assertThatThrownBy(() -> SecretMaterialLoader.required(
                null,
                "test-only-short",
                "CPF HMAC"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pelo menos 32")
                .hasMessageNotContaining("test-only-short");
    }
}
