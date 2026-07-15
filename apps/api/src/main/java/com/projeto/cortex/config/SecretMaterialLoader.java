package com.projeto.cortex.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Loads secret material without introducing repository or environment defaults. */
public final class SecretMaterialLoader {

    private static final int MINIMUM_SECRET_LENGTH = 32;

    private SecretMaterialLoader() {
    }

    public static byte[] required(Path file, String inline, String label) {
        boolean hasFile = file != null;
        boolean hasInline = inline != null && !inline.isBlank();
        if (hasFile && hasInline) {
            throw new IllegalStateException(
                    label + " deve usar arquivo ou valor inline, nunca ambos."
            );
        }

        String value;
        try {
            value = hasFile
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : inline;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Não foi possível ler o secret file de " + label + ".",
                    exception
            );
        }

        String normalized = value == null ? null : value.strip();
        if (normalized == null
                || normalized.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    label + " deve conter pelo menos 32 caracteres."
            );
        }
        return normalized.getBytes(StandardCharsets.UTF_8);
    }
}
