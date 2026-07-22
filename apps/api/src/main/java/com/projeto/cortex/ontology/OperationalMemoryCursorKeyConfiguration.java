package com.projeto.cortex.ontology;

import com.projeto.cortex.common.SecurityRuntimeMode;
import com.projeto.cortex.config.SecretMaterialLoader;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class OperationalMemoryCursorKeyConfiguration {

    private final Environment environment;

    OperationalMemoryCursorKeyConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    OperationalMemoryCursorSigner operationalMemoryCursorSigner(
            @Value("${cortex.ontology.memory.cursor-hmac.current-key-id:}")
            String currentKeyId,
            @Value("${cortex.ontology.memory.cursor-hmac.current-key-file:}")
            String currentKeyFile,
            @Value("${cortex.ontology.memory.cursor-hmac.current-key-inline:}")
            String currentKeyInline,
            @Value("${cortex.ontology.memory.cursor-hmac.previous-key-id:}")
            String previousKeyId,
            @Value("${cortex.ontology.memory.cursor-hmac.previous-key-file:}")
            String previousKeyFile,
            @Value("${cortex.ontology.memory.cursor-hmac.previous-key-inline:}")
            String previousKeyInline
    ) {
        validateSecretSources(
                currentKeyFile,
                currentKeyInline,
                previousKeyId,
                previousKeyFile,
                previousKeyInline
        );
        byte[] current = load(currentKeyFile, currentKeyInline, "Cursor de Memória HMAC");
        boolean hasPrevious = hasText(previousKeyId)
                || hasText(previousKeyFile)
                || hasText(previousKeyInline);
        byte[] previous = hasPrevious
                ? load(
                        previousKeyFile,
                        previousKeyInline,
                        "Cursor de Memória HMAC anterior"
                )
                : null;
        try {
            return new OperationalMemoryCursorSigner(
                    new OperationalMemoryCursorKeyring(
                            normalized(currentKeyId),
                            current,
                            hasPrevious ? normalized(previousKeyId) : null,
                            previous
                    )
            );
        } finally {
            Arrays.fill(current, (byte) 0);
            if (previous != null) {
                Arrays.fill(previous, (byte) 0);
            }
        }
    }

    private void validateSecretSources(
            String currentFile,
            String currentInline,
            String previousId,
            String previousFile,
            String previousInline
    ) {
        if (SecurityRuntimeMode.isLocalOrTestOnly(environment)) {
            return;
        }
        if (!hasText(currentFile) || hasText(currentInline)) {
            throw new IllegalStateException(
                    "Cursor de Memória HMAC em runtime exige chave atual em arquivo secreto."
            );
        }
        boolean previousConfigured = hasText(previousId)
                || hasText(previousFile)
                || hasText(previousInline);
        if (previousConfigured
                && (!hasText(previousId)
                || !hasText(previousFile)
                || hasText(previousInline))) {
            throw new IllegalStateException(
                    "Cursor de Memória HMAC anterior exige ID e arquivo secreto."
            );
        }
    }

    private byte[] load(String file, String inline, String label) {
        String normalizedFile = normalized(file);
        return SecretMaterialLoader.required(
                normalizedFile == null ? null : Path.of(normalizedFile),
                normalized(inline),
                label
        );
    }

    private String normalized(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
