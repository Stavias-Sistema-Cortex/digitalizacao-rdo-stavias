package com.projeto.cortex.obras;

public record ObraSeedImportResult(
        String status,
        String caminhoArquivo,
        int registrosLidos,
        int registrosInseridos,
        int registrosIgnorados,
        int registrosComErro
) {
}
