package com.projeto.cortex.importacao;

public record RdoImportacaoConfirmRequest(
        String estrategiaDuplicidade,
        String usuarioId,
        boolean dryRun
) {
}
