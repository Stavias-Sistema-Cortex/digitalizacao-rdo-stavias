package com.projeto.cortex.storage.migrate;

import java.util.Objects;
import java.util.regex.Pattern;

public record ObjectStorageMigrationResult(
        long selected,
        long copied,
        long alreadyVerified,
        long missing,
        long mismatched,
        long failed,
        long bytes,
        String manifestSha256
) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public ObjectStorageMigrationResult {
        if (selected < 0 || copied < 0 || alreadyVerified < 0
                || missing < 0 || mismatched < 0 || failed < 0
                || bytes < 0) {
            throw new IllegalArgumentException(
                    "Contadores da migração de objetos não podem ser negativos."
            );
        }
        Objects.requireNonNull(manifestSha256, "manifestSha256");
        if (!SHA256.matcher(manifestSha256).matches()) {
            throw new IllegalArgumentException(
                    "O digest do manifesto de objetos é inválido."
            );
        }
        if (copied + alreadyVerified + missing + mismatched + failed
                != selected) {
            throw new IllegalArgumentException(
                    "Os totais da migração de objetos são inconsistentes."
            );
        }
    }

    public boolean complete() {
        return missing == 0 && mismatched == 0 && failed == 0;
    }
}
